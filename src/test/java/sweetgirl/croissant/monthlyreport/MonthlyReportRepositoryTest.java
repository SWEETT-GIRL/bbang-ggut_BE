package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.MONTH;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.PAYLOAD;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validation;
import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.event.PushRequestedEvent;
import sweetgirl.croissant.alarm.service.NotificationRecorder;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMember;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMemberId;
import sweetgirl.croissant.monthlyreport.enums.MonthlyReportStatus;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportJobRepository;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportNotificationRepository;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository.PublishedReport;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportSourceHistoryRepository;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportNotificationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportPayloadCodec;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportPublicationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportQueryService;

/** 전용 PostgreSQL에서 실행한다. 운영/개발 스키마를 건드리지 않고 임의 이름의 테스트 스키마만 만든다. */
@Tag("lab")
@EnabledIfEnvironmentVariable(named = "MONTHLY_REPORT_TEST_JDBC_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MonthlyReportRepositoryTest {

	private final String schema = "monthly_report_test_" + UUID.randomUUID().toString().replace("-", "");
	private JdbcTemplate rootJdbc;
	private JdbcTemplate jdbc;
	private MonthlyReportRepository repository;
	private TransactionTemplate transactions;
	private AnnotationConfigApplicationContext jpaContext;

	@BeforeAll
	void createSchema() {
		var source = new DriverManagerDataSource(System.getenv("MONTHLY_REPORT_TEST_JDBC_URL"),
			System.getenv("MONTHLY_REPORT_TEST_DB_USER"), System.getenv("MONTHLY_REPORT_TEST_DB_PASSWORD"));
		rootJdbc = new JdbcTemplate(source);
		rootJdbc.execute("CREATE SCHEMA " + schema);
		var scopedSource = new DriverManagerDataSource(source.getUrl(), source.getUsername(), source.getPassword());
		scopedSource.setSchema(schema);
		jdbc = new JdbcTemplate(scopedSource);
		jdbc.execute("CREATE TABLE member (id bigint PRIMARY KEY)");
		jdbc.execute(
			"CREATE TABLE member_world (member_id bigint PRIMARY KEY, selected_character text, max_record_count int)");
		jdbc.execute("CREATE TABLE world_character (code text PRIMARY KEY, name text)");
		jdbc.execute("CREATE TABLE world_planet (code text PRIMARY KEY, name text)");
		jdbc.execute("CREATE TABLE recorded_notice (id bigint GENERATED ALWAYS AS IDENTITY, link text)");
		jdbc.execute("CREATE TABLE bread_record (id bigint PRIMARY KEY, member_id bigint, deleted_at timestamp)");
		jdbc.execute("""
				CREATE TABLE bread_record_photo (id bigint PRIMARY KEY, record_id bigint, aws_key text,
					resized_key_320 text, resized_key_640 text, resized_key_1024 text)
				""");
		new ResourceDatabasePopulator(new ClassPathResource(
			"db/migration/V20261005222045__create_monthly_report_member.sql"),
			new ClassPathResource("db/migration/V20261005222046__add_monthly_report_appearance.sql"),
			new ClassPathResource("db/migration/V20261005222047__add_bread_photo_reference.sql"),
			new ClassPathResource("db/migration/V20261005222048__link_monthly_report_bread_photos.sql"))
			.execute(scopedSource);
		var factory = new LocalContainerEntityManagerFactoryBean();
		factory.setDataSource(scopedSource);
		factory.setManagedTypes(PersistenceManagedTypes.of(MonthlyReportMember.class.getName()));
		factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.default_schema", schema));
		factory.afterPropertiesSet();
		EntityManagerFactory emf = factory.getObject();
		jpaContext = new AnnotationConfigApplicationContext();
		jpaContext.registerBean("entityManagerFactory", EntityManagerFactory.class, () -> emf);
		jpaContext.registerBean("transactionManager", JpaTransactionManager.class,
			() -> new JpaTransactionManager(emf));
		jpaContext.registerBean(HibernateJpaDialect.class);
		jpaContext.register(JpaConfig.class);
		jpaContext.refresh();
		repository = jpaContext.getBean(MonthlyReportRepository.class);
		transactions = new TransactionTemplate(jpaContext.getBean(JpaTransactionManager.class));
	}

	@BeforeEach
	void clearFixtures() {
		jdbc.execute("TRUNCATE monthly_report_member, bread_record_photo, bread_record, member, recorded_notice, "
			+ "member_world, world_character, world_planet");
		jdbc.update("INSERT INTO member (id) VALUES (123), (456)");
	}

	@AfterAll
	void dropSchema() {
		if (jpaContext != null) {
			jpaContext.close();
		}
		if (rootJdbc != null) {
			rootJdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
		}
	}

	@Test
	void storesJsonAndScopesQueriesByMemberAndMonth() {
		assertThat(publish(123L, MONTH, PAYLOAD)).isTrue();
		assertThat(repository.findById(new MonthlyReportMemberId(123L, MONTH))).hasValueSatisfying(snapshot -> {
			assertThat(snapshot.getStatus()).isEqualTo(MonthlyReportStatus.PUBLISHED);
			assertThat(snapshot.getPayload()).contains("소금이");
		});
		assertThat(repository.findById(new MonthlyReportMemberId(456L, MONTH))).isEmpty();
		assertThat(repository.findById(new MonthlyReportMemberId(123L, "2025-11"))).isEmpty();
		assertThat(jdbc.queryForObject("SELECT raw_metrics->>'records' FROM monthly_report_member", String.class))
			.isEqualTo("12");
	}

	@Test
	void retryDoesNotChangePublishedPayloadOrTimestamp() {
		assertThat(publish(123L, MONTH, PAYLOAD)).isTrue();
		var original = repository.findById(new MonthlyReportMemberId(123L, MONTH));
		var time = jdbc.queryForObject("SELECT published_at FROM monthly_report_member", java.sql.Timestamp.class);
		assertThat(publish(123L, MONTH, PAYLOAD.replace("소금이", "바뀐 이름"))).isFalse();
		assertThat(repository.findById(new MonthlyReportMemberId(123L, MONTH)).orElseThrow().getPayload())
			.isEqualTo(original.orElseThrow().getPayload());
		assertThat(jdbc.queryForObject("SELECT published_at FROM monthly_report_member", java.sql.Timestamp.class))
			.isEqualTo(time);
	}

	@Test
	void concurrentPublicationHasOneWinner() throws Exception {
		var executor = Executors.newFixedThreadPool(2);
		try {
			var start = new CountDownLatch(1);
			var first = executor.submit(() -> {
				start.await();
				return publish(123L, MONTH, PAYLOAD);
			});
			var second = executor.submit(() -> {
				start.await();
				return publish(123L, MONTH, PAYLOAD);
			});
			start.countDown();
			assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder(true, false);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM monthly_report_member", Integer.class)).isEqualTo(1);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void failedOrWithheldSnapshotsCanBePublishedAfterRetry() {
		jdbc.update("INSERT INTO monthly_report_member (member_id, year_month, status) VALUES (123, ?, 'FAILED')",
			MONTH);
		assertThat(repository.findById(new MonthlyReportMemberId(123L, MONTH)).orElseThrow().getStatus())
			.isEqualTo(MonthlyReportStatus.FAILED);
		assertThat(publish(123L, MONTH, PAYLOAD)).isTrue();
		jdbc.update("INSERT INTO monthly_report_member (member_id, year_month, status) VALUES (456, ?, 'WITHHELD')",
			MONTH);
		assertThat(publish(456L, MONTH, PAYLOAD.replace("123", "456"))).isTrue();
	}

	@Test
	void checksPublicationConstraintsAndMemberDeletion() {
		assertThatThrownBy(() -> publish(123L, "2025-13", PAYLOAD)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> publish(789L, MONTH, PAYLOAD)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update(
			"INSERT INTO monthly_report_member (member_id, year_month, status) VALUES (123, ?, 'PUBLISHED')", MONTH))
			.isInstanceOf(DataIntegrityViolationException.class);
		publish(123L, MONTH, PAYLOAD);
		jdbc.update("DELETE FROM member WHERE id = 123");
		assertThat(repository.findById(new MonthlyReportMemberId(123L, MONTH))).isEmpty();
	}

	@Test
	void listFiltersOwnerAndPublicationStatusAndSortsMonthsBeforePagination() {
		publish(123L, "2025-10", PAYLOAD);
		publish(123L, "2025-12", PAYLOAD);
		publish(123L, "2025-11", PAYLOAD);
		publish(456L, "2026-03", PAYLOAD);
		jdbc.update("""
				INSERT INTO monthly_report_member (member_id, year_month, status)
				VALUES (123, '2026-02', 'WITHHELD'), (123, '2026-01', 'FAILED')
				""");
		assertThat(repository.findPublished(123L, PageRequest.of(0, 2)).getContent())
			.extracting(PublishedReport::getYearMonth)
			.containsExactly("2025-12", "2025-11");
		assertThat(repository.findPublished(123L, PageRequest.of(1, 2)).getContent())
			.extracting(PublishedReport::getYearMonth)
			.containsExactly("2025-10");
		assertThat(repository.findPublished(123L, PageRequest.of(2, 2)).getContent()).isEmpty();
		assertThat(repository.findPublished(789L, PageRequest.of(0, 12)).getContent()).isEmpty();
		assertThat(repository.findPublished(123L, PageRequest.of(0, 2)).getContent()).allSatisfy(report -> {
			assertThat(report.getCharacterCode()).isEqualTo("cat");
			assertThat(report.getCharacterName()).isEqualTo("냥이");
			assertThat(report.getCharacterImageKey()).isNull();
		});
	}

	@Test
	void photoSourceMigrationLinksOnlyUnambiguousOwnedPhotosAndPreservesPayload() throws Exception {
		jdbc.update("INSERT INTO bread_record (id, member_id) VALUES (1, 123), (2, 123), (3, 456)");
		jdbc.update("""
				INSERT INTO bread_record_photo (id, record_id, aws_key)
				VALUES (1, 1, 'unique'), (2, 1, 'shared'), (3, 2, 'shared'), (4, 3, 'foreign')
				""");
		String oldKey = "bread-records/members/123/images/a";
		publish(123L, MONTH, PAYLOAD.replace(oldKey, "unique"));
		publish(123L, "2025-11", PAYLOAD.replace(oldKey, "shared"));
		publish(123L, "2025-10", PAYLOAD.replace(oldKey, "foreign"));
		String original = repository.findById(new MonthlyReportMemberId(123L, MONTH)).orElseThrow().getPayload();
		new ResourceDatabasePopulator(new ClassPathResource(
			"db/migration/V20261005222048__link_monthly_report_bread_photos.sql")).execute(jdbc.getDataSource());
		var mapper = new ObjectMapper();
		var snapshot = repository.findById(new MonthlyReportMemberId(123L, MONTH)).orElseThrow();
		assertThat(snapshot.getPayload()).isEqualTo(original);
		var source = mapper.readTree(snapshot.getRawMetrics()).at("/breadPhotoSources/소금빵");
		assertThat(source.path("recordId").asLong()).isEqualTo(1);
		assertThat(source.path("photoReferenceId").asText()).isEqualTo(jdbc.queryForObject(
			"SELECT reference_id::text FROM bread_record_photo WHERE id = 1", String.class));
		for (String month : List.of("2025-11", "2025-10")) {
			var unresolved = repository.findById(new MonthlyReportMemberId(123L, month)).orElseThrow();
			assertThat(mapper.readTree(unresolved.getRawMetrics()).has("breadPhotoSources")).isFalse();
		}
	}

	@Test
	void appearanceAndPayloadStayFrozenAfterProfileAndCatalogChanges() throws Exception {
		var original = MonthlyReportFixtures.payload();
		original.withObject("/character").put("imageKey", "reports/characters/v1/cat.webp");
		assertThat(publish(123L, MONTH, original.toString())).isTrue();
		jdbc.update("INSERT INTO member_world VALUES (123, 'cat', 12)");
		jdbc.update("INSERT INTO world_character VALUES ('cat', '냥이')");
		jdbc.update("INSERT INTO world_planet VALUES ('earth', '지구')");
		var reader = mock(MemberReader.class);
		var member = MonthlyReportFixtures.eligibleMember("현재 닉네임");
		given(reader.getMember(123L)).willReturn(member);
		try (var factory = Validation.buildDefaultValidatorFactory()) {
			var codec = new MonthlyReportPayloadCodec(new ObjectMapper(), factory.getValidator());
			var query = new MonthlyReportQueryService(reader, repository, codec, mock(BreadRecordTagRepository.class));
			var before = query.getReport(123L, MONTH);
			jdbc.update(
				"UPDATE member_world SET selected_character = 'dog', max_record_count = 9999 WHERE member_id = 123");
			jdbc.update("UPDATE world_character SET name = '바뀐 캐릭터 이름' WHERE code = 'cat'");
			jdbc.update("UPDATE world_planet SET name = '바뀐 행성 이름' WHERE code = 'earth'");
			member.resetNickname("변경한 닉네임");
			assertThat(query.getReport(123L, MONTH)).isEqualTo(before.withNickname("변경한 닉네임"));
			assertThat(query.getReports(123L, 0, 12).content()).singleElement()
				.satisfies(report -> assertThat(report.character()).isEqualTo(before.character()));
			assertThat(before.nickname()).isEqualTo("현재 닉네임");
			assertThat(repository.findById(new MonthlyReportMemberId(123L, MONTH)).orElseThrow().getPayload())
				.contains("소금이");
			assertThat(before.character().code()).isEqualTo("cat");
			assertThat(before.planet().code()).isEqualTo("earth");
			assertThat(before.activity().artwork().imageKey()).isEqualTo("reports/types/v1/earth/cat/record.webp");
		}
		var changed = original.deepCopy();
		changed.withObject("/character").put("code", "dog").put("name", "멍이").putNull("imageKey");
		changed.withObject("/planet").put("code", "mars").put("name", "화성");
		assertThat(publish(123L, MONTH, changed.toString())).isFalse();
		var snapshot = repository.findById(new MonthlyReportMemberId(123L, MONTH)).orElseThrow();
		assertThat(snapshot.getCharacterCode()).isEqualTo("cat");
		assertThat(snapshot.getCharacterName()).isEqualTo("냥이");
		assertThat(snapshot.getCharacterImageKey()).isEqualTo("reports/characters/v1/cat.webp");
		assertThat(snapshot.getPlanetCode()).isEqualTo("earth");
		assertThat(snapshot.getPlanetName()).isEqualTo("지구");
	}

	@Test
	void entityDoesNotDirtyCheckPublishedAppearanceAndDatabaseRejectsDivergence() {
		publish(123L, MONTH, PAYLOAD);
		transactions.executeWithoutResult(status -> {
			var snapshot = repository.findById(new MonthlyReportMemberId(123L, MONTH)).orElseThrow();
			ReflectionTestUtils.setField(snapshot, "characterCode", "dog");
			repository.flush();
		});
		assertThat(repository.findById(new MonthlyReportMemberId(123L, MONTH)).orElseThrow().getCharacterCode())
			.isEqualTo("cat");
		assertThatThrownBy(() -> jdbc.update("UPDATE monthly_report_member SET planet_code = 'mars'"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void appearanceMigrationBackfillsFromOldPayload() {
		String oldSchema = schema + "_old";
		rootJdbc.execute("CREATE SCHEMA " + oldSchema);
		var source = new DriverManagerDataSource(System.getenv("MONTHLY_REPORT_TEST_JDBC_URL"),
			System.getenv("MONTHLY_REPORT_TEST_DB_USER"), System.getenv("MONTHLY_REPORT_TEST_DB_PASSWORD"));
		source.setSchema(oldSchema);
		var oldJdbc = new JdbcTemplate(source);
		try {
			oldJdbc.execute("CREATE TABLE member (id bigint PRIMARY KEY)");
			oldJdbc.update("INSERT INTO member VALUES (123)");
			new ResourceDatabasePopulator(new ClassPathResource(
				"db/migration/V20261005222045__create_monthly_report_member.sql")).execute(source);
			oldJdbc.update(
				"""
								INSERT INTO monthly_report_member
								(member_id, year_month, status, payload, raw_metrics,
						calculation_version, generated_at, published_at)
								VALUES (123, '2025-12', 'PUBLISHED', CAST(? AS jsonb), '{}', 'v1', now(), now())
								""",
				PAYLOAD);
			new ResourceDatabasePopulator(new ClassPathResource(
				"db/migration/V20261005222046__add_monthly_report_appearance.sql")).execute(source);
			var row = oldJdbc.queryForMap("SELECT character_code, character_name, character_image_key, "
				+ "planet_code, planet_name FROM monthly_report_member");
			assertThat(row).containsEntry("character_code", "cat").containsEntry("character_name", "냥이")
				.containsEntry("character_image_key", null).containsEntry("planet_code", "earth")
				.containsEntry("planet_name", "지구");
		} finally {
			rootJdbc.execute("DROP SCHEMA " + oldSchema + " CASCADE");
		}
	}

	@Test
	void publishesPushEventOnlyAfterCommitAndRollsBackNoticeWithSnapshot() {
		try (var context = new AnnotationConfigApplicationContext();
				var factory = Validation.buildDefaultValidatorFactory()) {
			context.registerBean(TransactionalEventListenerFactory.class);
			context.registerBean(PushCapture.class);
			context.refresh();
			var capture = context.getBean(PushCapture.class);
			var recorder = mock(NotificationRecorder.class);
			given(recorder.recordFor(any(), any(), any())).willAnswer(invocation -> {
				Notification notification = invocation.getArgument(0);
				Long id = jdbc.queryForObject("INSERT INTO recorded_notice (link) VALUES (?) RETURNING id",
					Long.class, notification.getDeepLink());
				ReflectionTestUtils.setField(notification, "id", id);
				return notification;
			});
			Member member = MonthlyReportFixtures.eligibleMember("소금이");
			ReflectionTestUtils.setField(member, "id", 123L);
			var reader = mock(MemberReader.class);
			given(reader.getMember(123L)).willReturn(member);
			var mapper = new ObjectMapper();
			var codec = new MonthlyReportPayloadCodec(mapper, factory.getValidator());
			var report = codec.read(PAYLOAD, 123L, MONTH);
			var publication = new MonthlyReportPublicationService(reader, repository, codec,
				new MonthlyReportNotificationService(recorder, context));
			var metrics = mapper.createObjectNode().put("records", 12);
			Instant generatedAt = Instant.parse("2026-01-01T00:00:00Z");

			// 롤백되는 발행은 스냅샷과 알림이 함께 사라지고 AFTER_COMMIT 이벤트도 실행되지 않는다.
			transactions.executeWithoutResult(status -> {
				assertThat(publication.publish(123L, MONTH, report, metrics, "test-v1", generatedAt)).isTrue();
				assertThat(capture.events).isEmpty();
				status.setRollbackOnly();
			});
			assertThat(repository.findById(new MonthlyReportMemberId(123L, MONTH))).isEmpty();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM recorded_notice", Integer.class)).isZero();
			assertThat(capture.events).isEmpty();

			transactions.executeWithoutResult(status -> {
				assertThat(publication.publish(123L, MONTH, report, metrics, "test-v1", generatedAt)).isTrue();
				assertThat(capture.events).isEmpty();
			});
			assertThat(capture.events).hasSize(1);
			assertThat(capture.events.get(0).memberId()).isEqualTo(123L);
			assertThat(jdbc.queryForObject("SELECT link FROM recorded_notice", String.class))
				.isEqualTo("/reports/monthly/2025-12");
			transactions.executeWithoutResult(
				status -> assertThat(publication.publish(123L, MONTH, report, metrics, "test-v1", generatedAt))
					.isFalse());
			assertThat(capture.events).hasSize(1);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM recorded_notice", Integer.class)).isEqualTo(1);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableTransactionManagement
	@EnableJpaRepositories(basePackageClasses = MonthlyReportRepository.class,
			// 이 테스트의 최소 스키마에는 원천 이력 저장소를 등록하지 않는다.
			excludeFilters = @Filter(type = ASSIGNABLE_TYPE, classes = {MonthlyReportSourceHistoryRepository.class,
				MonthlyReportJobRepository.class, MonthlyReportNotificationRepository.class}))
	static class JpaConfig {
		// 보고서 저장소만 독립적인 JPA 컨텍스트에서 검증한다.
	}

	static class PushCapture {
		private final List<PushRequestedEvent> events = new ArrayList<>();

		@TransactionalEventListener
		public void afterCommit(PushRequestedEvent event) {
			events.add(event);
		}
	}

	private boolean publish(Long memberId, String month, String payload) {
		return repository.publish(memberId, month, payload, "{\"records\":12}", "test-v1",
			Instant.parse("2026-01-01T00:00:00Z"), Instant.now()) == 1;
	}
}

package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.MONTH;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.PAYLOAD;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.validation.Validation;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportSourceSnapshot;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMemberId;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportSourceHistoryRepository;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportActivityPolicy;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportBreadAggregationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportGenerationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportNotificationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportPayloadCodec;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportPublicationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportQueryService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportRecordAggregationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportSourceReader;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportTownAggregationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportViewedStoreAggregationService;

@Tag("lab")
@EnabledIfEnvironmentVariable(named = "MONTHLY_REPORT_TEST_JDBC_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MonthlyReportBreadAggregationTest {

	private final String schema = "monthly_bread_" + UUID.randomUUID().toString().replace("-", "");
	private JdbcTemplate rootJdbc;
	private JdbcTemplate jdbc;
	private LocalContainerEntityManagerFactoryBean factory;
	private EntityManager entityManager;
	private MonthlyReportRepository repository;
	private MonthlyReportBreadAggregationService aggregation;
	private BreadRecordTagRepository tagRepository;
	private MonthlyReportSourceReader sourceReader;

	@BeforeAll
	void setUp() {
		var source = new DriverManagerDataSource(System.getenv("MONTHLY_REPORT_TEST_JDBC_URL"),
			System.getenv("MONTHLY_REPORT_TEST_DB_USER"), System.getenv("MONTHLY_REPORT_TEST_DB_PASSWORD"));
		rootJdbc = new JdbcTemplate(source);
		rootJdbc.execute("CREATE SCHEMA " + schema);
		source.setSchema(schema);
		jdbc = new JdbcTemplate(source);
		new ResourceDatabasePopulator(new ClassPathResource("monthly-report-bread-aggregation.sql"),
			new ClassPathResource("db/migration/V20261005222045__create_monthly_report_member.sql"),
			new ClassPathResource("db/migration/V20261005222046__add_monthly_report_appearance.sql"),
			new ClassPathResource("db/migration/V20261005222047__add_bread_photo_reference.sql"),
			new ClassPathResource("db/migration/V20261005222048__link_monthly_report_bread_photos.sql"))
			.execute(source);
		ResourceDatabasePopulator historyMigration = new ResourceDatabasePopulator(new ClassPathResource(
			"db/migration/V20261005222049__create_monthly_report_source_history.sql"));
		historyMigration.setSeparator(ScriptUtils.EOF_STATEMENT_SEPARATOR);
		new TransactionTemplate(new DataSourceTransactionManager(source))
			.executeWithoutResult(status -> historyMigration.execute(source));
		jdbc.update("UPDATE monthly_report_history_coverage SET covered_from = '2025-12-01'");
		factory = new LocalContainerEntityManagerFactoryBean();
		factory.setDataSource(source);
		// 빵로그 엔티티가 참조하는 회원·가게 등도 매핑하되 테스트용 원천 테이블만 생성한다.
		factory.setPackagesToScan("sweetgirl.croissant");
		factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none", "hibernate.default_schema", schema,
			"hibernate.physical_naming_strategy",
			"org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"));
		factory.afterPropertiesSet();
		entityManager = factory.getObject().createEntityManager();
		var repositories = new JpaRepositoryFactory(entityManager);
		repository = repositories.getRepository(MonthlyReportRepository.class);
		tagRepository = repositories.getRepository(BreadRecordTagRepository.class);
		sourceReader = new MonthlyReportSourceReader(
			repositories.getRepository(MonthlyReportSourceHistoryRepository.class), new ObjectMapper());
		aggregation = new MonthlyReportBreadAggregationService(sourceReader);
	}

	@AfterAll
	void cleanUp() {
		if (entityManager != null) {
			entityManager.close();
		}
		if (factory != null) {
			factory.destroy();
		}
		if (rootJdbc != null) {
			rootJdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
		}
	}

	private void changesBeforeMonthEnd() {
		jdbc.update("UPDATE monthly_report_source_history SET recorded_at = '2025-12-31 23:59:59'");
		entityManager.clear();
	}

	private MonthlyReportBreadAggregationService.Result aggregateBeforePublication() {
		changesBeforeMonthEnd();
		return aggregation.aggregate(123L, MONTH);
	}

	private MonthlyReportResponse readReport(MonthlyReportQueryService query) {
		entityManager.clear();
		return query.getReport(123L, MONTH);
	}

	@Test
	void legacyLookupChecksPhotoOwnerDeletionAndMatchingRecord() {
		entityManager.clear();
		assertThat(tagRepository.findMonthlyReportPhotoTags(123L, Set.of(),
			Set.of("old-salt", "foreign", "deleted", "missing")))
			.allSatisfy(tag -> assertThat(tag.getPhoto().getAwsKey()).isEqualTo("old-salt"))
			.isNotEmpty();
	}

	@Test
	void countsDistinctStoreIdsSeparateManualRecordsAndMonthEndDeletions() {
		jdbc.update("INSERT INTO member VALUES (901)");
		jdbc.update("""
				INSERT INTO bread_record (id, member_id, store_id, store_name, eaten_at, created_at, deleted_at)
				VALUES
				(101, 901, 11, '같은 이름', '2025-12-01', '2025-12-01', NULL),
				(102, 901, 11, '다른 이름', '2025-12-02', '2025-12-02', NULL),
				(103, 901, NULL, '같은 이름', '2025-12-03', '2025-12-03', NULL),
				(104, 901, NULL, '같은 이름', '2025-12-04', '2025-12-04', NULL),
				(105, 901, 12, '삭제된 가게', '2025-12-05', '2025-12-05', '2026-01-01 00:10:00'),
				(106, 901, 13, '경계 삭제', '2025-12-06', '2025-12-06', '2026-01-01'),
				(107, 901, 14, '월중 삭제', '2025-12-07', '2025-12-07', '2025-12-31 23:59:59'),
				(108, 901, 15, '월말 후 작성', '2025-12-08', '2026-01-01', NULL),
				(109, 901, 16, '다음 달 섭취', '2026-01-01', '2025-12-31', NULL),
				(110, 901, 17, '이전 달 섭취', '2025-11-30 23:59:59', '2025-11-30', NULL)
				""");
		jdbc.update("""
				INSERT INTO bread_record_photo (id, record_id, sort_order, aws_key)
				SELECT id, id, 0, 'boundary-photo-' || id FROM bread_record WHERE id BETWEEN 105 AND 108
				""");
		jdbc.update("""
				INSERT INTO bread_record_tag (id, record_id, photo_id, name, bread_category)
				SELECT id, id, id, '소금빵', '소금빵' FROM bread_record WHERE id BETWEEN 105 AND 108
				""");
		changesBeforeMonthEnd();
		try (var validation = Validation.buildDefaultValidatorFactory()) {
			MonthlyReportPayloadCodec codec = new MonthlyReportPayloadCodec(new ObjectMapper(),
				validation.getValidator());
			MonthlyReportRecordAggregationService service = new MonthlyReportRecordAggregationService(
				sourceReader, repository, codec);
			assertThat(service.aggregate(901L, MONTH))
				.isEqualTo(new MonthlyReportRecordAggregationService.Result(6, 5, null));
			assertThat(service.aggregate(999L, MONTH))
				.isEqualTo(new MonthlyReportRecordAggregationService.Result(0, 0, null));
		}
		MonthlyReportBreadAggregationService.Result breads = aggregation.aggregate(901L, MONTH);
		assertThat(breads.total()).isEqualTo(2);
		assertThat(breads.photoSources().get("소금빵").recordId()).isEqualTo(106);
		assertThat(tagRepository.findMonthlyReportPhotoTags(901L,
			Set.of(breads.photoSources().get("소금빵").photoReferenceId()), Set.of())).isEmpty();
		// 실제 삭제 경로처럼 원본 태그·사진이 사라져도 월말 이력은 남는다.
		jdbc.update("DELETE FROM bread_record_tag WHERE record_id IN (105, 106)");
		jdbc.update("DELETE FROM bread_record_photo WHERE record_id IN (105, 106)");
		entityManager.clear();
		assertThat(aggregation.aggregate(901L, MONTH).total()).isEqualTo(2);
	}

	@Test
	void countsOwnerCoursesAndRetainedMonthlyAdditionsWithoutActorEvents() {
		jdbc.update("INSERT INTO member VALUES (777), (778)");
		jdbc.update("""
				INSERT INTO courses (id, member_id, created_at, deleted_at) VALUES
				(201, 777, '2025-11-01', NULL),
				(202, 777, '2025-12-01', '2026-01-01 00:10:00'),
				(203, 777, '2025-12-01', '2025-12-31 23:59:59'),
				(204, 778, '2025-12-01', NULL),
				(205, 777, '2026-01-01', NULL),
				(206, 777, '2025-12-31 23:59:59', NULL),
				(207, 777, '2025-12-01', '2026-01-01')
				""");
		jdbc.update("""
				INSERT INTO course_stores (id, course_id, store_id, created_at, deleted_at) VALUES
				(201, 201, 11, '2025-12-01', NULL),
				(202, 202, 11, '2025-12-02', '2026-01-01 00:10:00'),
				(203, 202, 12, '2025-12-01', '2026-01-01 00:10:00'),
				(204, 203, 13, '2025-12-01', NULL),
				(205, 204, 14, '2025-12-01', NULL),
				(206, 205, 15, '2026-01-01', NULL),
				(207, 201, 16, '2025-12-01', '2025-12-31 23:59:59'),
				(208, 201, 17, '2025-11-01', NULL),
				(209, 207, 18, '2025-12-31 23:59:59', '2026-01-01'),
				(210, 206, 19, '2026-01-01', NULL)
				""");
		jdbc.update("INSERT INTO store_plan (member_id, store_id, created_at) VALUES (777, 11, '2025-12-01')");
		changesBeforeMonthEnd();
		MonthlyReportSourceSnapshot result = sourceReader.read(777L, MONTH);
		assertThat(result.courses()).isEqualTo(3);
		assertThat(result.plannedStoreIds()).containsExactlyInAnyOrder(11L, 12L, 18L);
		assertThat(sourceReader.read(778L, MONTH).plannedStoreIds()).containsExactly(14L);
		assertThat(sourceReader.read(999L, MONTH).courses()).isZero();
		assertThat(sourceReader.read(999L, MONTH).plannedStoreIds()).isEmpty();
	}

	@Test
	void aggregatesRealTagsThenKeepsPublishedCountsAndPhotoChoiceAfterDeletion() throws Exception {
		// 같은 로그의 다른 종류가 최고였던 빵이어도 소금빵 사진의 우선순위에는 영향을 주지 않는다.
		jdbc.update("INSERT INTO bread_record_loved_bread VALUES (1, 0, '베이글')");
		var result = aggregateBeforePublication();
		assertThat(result.total()).isEqualTo(9);
		assertThat(result.uncategorized()).isEqualTo(2);
		assertThat(result.breads()).containsExactly(
			new MonthlyReportResponse.Bread("소금빵", 5L, null),
			new MonthlyReportResponse.Bread("베이글", 1L, null),
			new MonthlyReportResponse.Bread("식빵", 1L, null));
		assertThat(aggregation.aggregate(789L, MONTH).breads()).isEmpty();
		assertThatThrownBy(() -> aggregation.aggregate(123L, "2025-10")).isInstanceOf(CustomException.class)
			.hasFieldOrPropertyWithValue("errorCode", MonthlyReportErrorCode.SOURCE_HISTORY_UNAVAILABLE);
		jdbc.update("INSERT INTO bread_record_loved_bread VALUES (1, 1, '소금빵')");
		result = aggregateBeforePublication();
		assertThat(result.photoSources().get("소금빵").recordId()).isEqualTo(1);
		// 같은 종류에 최고였던 빵이 여럿이면 그중 최신 기록을 사용한다. 다른 이름의 태그도 분류로 묶는다.
		jdbc.update("INSERT INTO bread_record_loved_bread VALUES (2, 0, '버터소금빵')");
		result = aggregateBeforePublication();
		assertThat(result.photoSources().get("소금빵").recordId()).isEqualTo(2);
		jdbc.update("UPDATE bread_record SET loved_bread = '소금빵' WHERE id = 3");
		assertThat(aggregateBeforePublication().photoSources().get("소금빵").photoReferenceId())
			.isEqualTo(jdbc.queryForObject("SELECT reference_id FROM bread_record_photo WHERE id = 4", UUID.class));
		jdbc.update("UPDATE bread_record SET loved_bread = NULL WHERE id = 3");

		var reader = mock(MemberReader.class);
		given(reader.getMember(123L)).willReturn(MonthlyReportFixtures.eligibleMember("현재 닉네임"));
		var notifications = mock(MonthlyReportNotificationService.class);
		var mapper = new ObjectMapper();
		try (var validation = Validation.buildDefaultValidatorFactory()) {
			var codec = new MonthlyReportPayloadCodec(mapper, validation.getValidator());
			var publication = new MonthlyReportPublicationService(reader, repository, codec, notifications);
			var records = new MonthlyReportRecordAggregationService(sourceReader, repository, codec);
			var generation = new MonthlyReportGenerationService(repository, codec, aggregation, records, publication,
				sourceReader, mock(MonthlyReportViewedStoreAggregationService.class),
				new MonthlyReportTownAggregationService(), new MonthlyReportActivityPolicy());
			var draft = codec.read(PAYLOAD, 123L, MONTH);
			var metrics = mapper.createObjectNode().put("records", 12);
			changesBeforeMonthEnd();
			entityManager.getTransaction().begin();
			assertThat(generation.publishFromDraft(draft, metrics, "test-v1")).isTrue();
			entityManager.getTransaction().commit();
			assertThat(metrics.has("breadAggregation")).isFalse();
			var snapshot = repository.findById(new MonthlyReportMemberId(123L, MONTH)).orElseThrow();
			String originalPayload = snapshot.getPayload();
			assertThat(codec.read(originalPayload, 123L, MONTH).summary().records()).isEqualTo(3);
			assertThat(codec.read(originalPayload, 123L, MONTH).summary().stores()).isEqualTo(3);
			assertThat(codec.read(originalPayload, 123L, MONTH).comparison().previousRecords()).isNull();
			assertThat(codec.read(originalPayload, 123L, MONTH).breads()).isEqualTo(result.breads());
			assertThat(codec.read(originalPayload, 123L, MONTH).summary().breads()).isEqualTo(9);
			assertThat(mapper.readTree(snapshot.getRawMetrics()).at("/breadAggregation/uncategorizedTags").asLong())
				.isEqualTo(2);

			var query = new MonthlyReportQueryService(reader, repository, codec, tagRepository);
			var reference = result.photoSources().get("소금빵");
			assertThat(codec.readPhotoSources(snapshot.getRawMetrics()).get("소금빵")).isEqualTo(reference);
			// 수정 저장은 사진 행을 재생성해도 참조를 유지한다. 원본→리사이즈 변경도 현재 키로 응답한다.
			jdbc.update("DELETE FROM bread_record_photo WHERE id = 2");
			jdbc.update("""
					INSERT INTO bread_record_photo (id, record_id, reference_id, sort_order, aws_key)
					VALUES (22, 2, ?, 1, 'edited-salt')
					""", reference.photoReferenceId());
			jdbc.update("UPDATE bread_record_tag SET photo_id = 22 WHERE id = 2");
			assertThat(readReport(query).breads().get(0))
				.isEqualTo(new MonthlyReportResponse.Bread("소금빵", 5L, "edited-salt"));
			jdbc.update("UPDATE bread_record_photo SET resized_key_640 = 'edited-salt-640' WHERE id = 22");
			assertThat(readReport(query).breads().get(0).imageKey()).isEqualTo("edited-salt-640");
			// 태그가 재생성되거나 이름이 바뀌어도 같은 빵 종류면 수정된 사진을 사용한다.
			jdbc.update("UPDATE bread_record_tag SET id = 22, name = '새 소금빵' WHERE id = 2");
			assertThat(readReport(query).breads().get(0).imageKey()).isEqualTo("edited-salt-640");
			jdbc.update("UPDATE bread_record_tag SET bread_category = '베이글' WHERE id = 22");
			assertThat(readReport(query).breads().get(0))
				.isEqualTo(new MonthlyReportResponse.Bread("소금빵", 5L, null));
			// 미분류 태그의 메뉴 분류 보완도 집계와 같은 규칙을 사용한다.
			jdbc.update("UPDATE bread_record_tag SET bread_category = '기타' WHERE id = 22");
			assertThat(readReport(query).breads().get(0).imageKey()).isEqualTo("edited-salt-640");
			jdbc.update("UPDATE bread_record_tag SET menu_id = NULL WHERE id = 22");
			assertThat(readReport(query).breads().get(0).imageKey()).isNull();
			jdbc.update("DELETE FROM bread_record_tag WHERE id = 22");
			assertThat(readReport(query).breads().get(0).imageKey()).isNull();
			// 같은 로그의 다른 사진에 있는 태그로 대표 사진을 노출하지 않는다.
			jdbc.update("INSERT INTO bread_record_photo (id, record_id, aws_key) VALUES (24, 2, 'other-salt')");
			jdbc.update("""
					INSERT INTO bread_record_tag (id, record_id, photo_id, name, bread_category)
					VALUES (22, 2, 24, '소금빵', '소금빵'), (23, 2, 24, '버터소금빵', '소금빵')
					""");
			assertThat(readReport(query).breads().get(0).imageKey()).isNull();
			jdbc.update("UPDATE bread_record_tag SET photo_id = 22 WHERE id IN (22, 23)");
			assertThat(readReport(query).breads().get(0))
				.isEqualTo(new MonthlyReportResponse.Bread("소금빵", 5L, "edited-salt-640"));
			jdbc.update("UPDATE bread_record SET deleted_at = CURRENT_TIMESTAMP WHERE id = 2");
			assertThat(readReport(query).breads().get(0).imageKey()).isNull();
			jdbc.update("UPDATE bread_record SET deleted_at = NULL WHERE id = 2");
			// 원본 로그와 연결이 다르면 같은 회원의 사진이어도 노출하지 않는다.
			jdbc.update("UPDATE bread_record_photo SET record_id = 3 WHERE id = 22");
			jdbc.update("UPDATE bread_record_tag SET record_id = 3 WHERE id IN (22, 23)");
			assertThat(readReport(query).breads().get(0).imageKey()).isNull();
			jdbc.update("UPDATE bread_record_photo SET record_id = 6 WHERE id = 22");
			jdbc.update("UPDATE bread_record_tag SET record_id = 6 WHERE id IN (22, 23)");
			assertThat(readReport(query).breads().get(0).imageKey()).isNull();
			jdbc.update("UPDATE bread_record_photo SET record_id = 2 WHERE id = 22");
			jdbc.update("UPDATE bread_record_tag SET record_id = 2 WHERE id IN (22, 23)");
			jdbc.update("DELETE FROM bread_record_photo WHERE id = 22");
			jdbc.update("""
					INSERT INTO bread_record_photo (id, record_id, sort_order, aws_key)
					VALUES (23, 2, 1, 'unrelated-new-photo')
					""");
			assertThat(readReport(query).breads().get(0).imageKey()).isNull();
			// 다른 소금빵 사진이 남아 있어도 삭제된 대표 사진을 다시 선택하거나 통계를 재계산하지 않는다.
			jdbc.update("UPDATE bread_record SET deleted_at = CURRENT_TIMESTAMP WHERE id = 2");
			jdbc.update("UPDATE bread_record_tag SET bread_category = '쿠키' WHERE id = 1");
			entityManager.clear();
			var visible = readReport(query);
			assertThat(visible.breads().get(0)).isEqualTo(new MonthlyReportResponse.Bread("소금빵", 5L, null));
			assertThat(visible.summary().breads()).isEqualTo(9);
			assertThat(visible.nickname()).isEqualTo("현재 닉네임");
			entityManager.getTransaction().begin();
			assertThat(generation.publishFromDraft(draft, metrics, "test-v2")).isFalse();
			entityManager.getTransaction().commit();
			assertThat(repository.findById(new MonthlyReportMemberId(123L, MONTH)).orElseThrow().getPayload())
				.isEqualTo(originalPayload);
			verify(notifications, times(1)).recordPublished(any(), any(), any());
		}
	}
}

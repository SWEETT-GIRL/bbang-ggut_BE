package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.MONTH;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.PAYLOAD;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Validation;
import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.event.PushRequestedEvent;
import sweetgirl.croissant.alarm.service.NotificationRecorder;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportListItem;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportNotificationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportPayloadCodec;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportPublicationService;

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

	@BeforeAll
	void createSchema() {
		var source = new DriverManagerDataSource(System.getenv("MONTHLY_REPORT_TEST_JDBC_URL"),
			System.getenv("MONTHLY_REPORT_TEST_DB_USER"), System.getenv("MONTHLY_REPORT_TEST_DB_PASSWORD"));
		rootJdbc = new JdbcTemplate(source);
		rootJdbc.execute("CREATE SCHEMA " + schema);
		var scopedSource = new DriverManagerDataSource(source.getUrl(), source.getUsername(), source.getPassword());
		scopedSource.setSchema(schema);
		jdbc = new JdbcTemplate(scopedSource);
		transactions = new TransactionTemplate(new DataSourceTransactionManager(scopedSource));
		jdbc.execute("CREATE TABLE member (id bigint PRIMARY KEY)");
		jdbc.execute("CREATE TABLE recorded_notice (id bigint GENERATED ALWAYS AS IDENTITY, link text)");
		jdbc.execute("CREATE TABLE bread_record (id bigint PRIMARY KEY, member_id bigint, deleted_at timestamp)");
		jdbc.execute("""
				CREATE TABLE bread_record_photo (id bigint PRIMARY KEY, record_id bigint, aws_key text,
					resized_key_320 text, resized_key_640 text, resized_key_1024 text)
				""");
		new ResourceDatabasePopulator(new ClassPathResource(
			"db/migration/V20261001131708__create_monthly_report_member.sql")).execute(scopedSource);
		repository = new MonthlyReportRepository(new NamedParameterJdbcTemplate(scopedSource));
	}

	@BeforeEach
	void clearFixtures() {
		jdbc.execute("TRUNCATE monthly_report_member, bread_record_photo, bread_record, member, recorded_notice");
		jdbc.update("INSERT INTO member (id) VALUES (123), (456)");
	}

	@AfterAll
	void dropSchema() {
		if (rootJdbc != null) {
			rootJdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
		}
	}

	@Test
	void storesJsonAndScopesQueriesByMemberAndMonth() {
		assertThat(publish(123L, MONTH, PAYLOAD)).isTrue();
		assertThat(repository.find(123L, MONTH)).hasValueSatisfying(snapshot -> {
			assertThat(snapshot.status()).isEqualTo("PUBLISHED");
			assertThat(snapshot.payload()).contains("소금이");
		});
		assertThat(repository.find(456L, MONTH)).isEmpty();
		assertThat(repository.find(123L, "2025-11")).isEmpty();
		assertThat(jdbc.queryForObject("SELECT raw_metrics->>'records' FROM monthly_report_member", String.class))
			.isEqualTo("12");
	}

	@Test
	void retryDoesNotChangePublishedPayloadOrTimestamp() {
		assertThat(publish(123L, MONTH, PAYLOAD)).isTrue();
		var original = repository.find(123L, MONTH);
		var time = jdbc.queryForObject("SELECT published_at FROM monthly_report_member", java.sql.Timestamp.class);
		assertThat(publish(123L, MONTH, PAYLOAD.replace("소금이", "바뀐 이름"))).isFalse();
		assertThat(repository.find(123L, MONTH)).isEqualTo(original);
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
		assertThat(repository.find(123L, MONTH).orElseThrow().status()).isEqualTo("FAILED");
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
		assertThat(repository.find(123L, MONTH)).isEmpty();
	}

	@Test
	void hidesDeletedAndForeignPhotosWhileAllowingOwnPrivateRecords() {
		jdbc.update("INSERT INTO bread_record (id, member_id) VALUES (1, 123), (2, 456)");
		jdbc.update("""
				INSERT INTO bread_record_photo (id, record_id, aws_key, resized_key_640)
				VALUES (1, 1, 'own-original', 'own-640'), (2, 2, 'foreign-original', 'foreign-640')
				""");
		var keys = Set.of("own-original", "own-640", "foreign-original", "foreign-640", "removed-photo");
		assertThat(repository.findVisiblePhotoKeys(123L, keys)).containsExactlyInAnyOrder("own-original", "own-640");
		jdbc.update("UPDATE bread_record SET deleted_at = CURRENT_TIMESTAMP WHERE id = 1");
		assertThat(repository.findVisiblePhotoKeys(123L, keys)).isEmpty();
		assertThat(repository.findVisiblePhotoKeys(123L, Set.of())).isEmpty();
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
		assertThat(repository.findPublished(123L, 0, 2)).extracting(MonthlyReportListItem::yearMonth)
			.containsExactly("2025-12", "2025-11");
		assertThat(repository.findPublished(123L, 2, 2)).extracting(MonthlyReportListItem::yearMonth)
			.containsExactly("2025-10");
		assertThat(repository.findPublished(123L, 3, 2)).isEmpty();
		assertThat(repository.findPublished(789L, 0, 12)).isEmpty();
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
			Member member = new Member("소금이");
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
			assertThat(repository.find(123L, MONTH)).isEmpty();
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

	static class PushCapture {
		private final List<PushRequestedEvent> events = new ArrayList<>();

		@TransactionalEventListener
		public void afterCommit(PushRequestedEvent event) {
			events.add(event);
		}
	}

	private boolean publish(Long memberId, String month, String payload) {
		return repository.publish(memberId, month, payload, "{\"records\":12}", "test-v1",
			Instant.parse("2026-01-01T00:00:00Z"), Instant.now());
	}
}

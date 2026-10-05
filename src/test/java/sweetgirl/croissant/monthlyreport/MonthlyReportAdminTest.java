package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.MONTH;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.PAYLOAD;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.service.NotificationRecorder;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportAsset;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportDraft;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportDraftId;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportPush;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportRun;
import sweetgirl.croissant.monthlyreport.admin.repository.MonthlyReportDraftRepository;
import sweetgirl.croissant.monthlyreport.admin.repository.MonthlyReportPushRepository;
import sweetgirl.croissant.monthlyreport.admin.repository.MonthlyReportRunRepository;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAdminPublisher;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAdminService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportOperationsService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportPreparation;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportRunWorker;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMember;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMemberId;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportNotification;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportNotificationRepository;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportSourceHistoryRepository;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportGenerationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportIngestRecoveryService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportNotificationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportPayloadCodec;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportPublicationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportQueryService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportTargetReader;

@Tag("lab")
@EnabledIfEnvironmentVariable(named = "MONTHLY_REPORT_TEST_JDBC_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MonthlyReportAdminTest {
	private final String schema = "monthly_admin_" + UUID.randomUUID().toString().replace("-", "");
	private final MonthlyReportPreparation preparation = mock(MonthlyReportPreparation.class);
	private final MonthlyReportTargetReader targets = mock(MonthlyReportTargetReader.class);
	private final MonthlyReportSourceHistoryRepository history = mock(MonthlyReportSourceHistoryRepository.class);
	private final MonthlyReportIngestRecoveryService recovery = mock(MonthlyReportIngestRecoveryService.class);
	private final MemberReader members = mock(MemberReader.class);
	private final NotificationRecorder recorder = mock(NotificationRecorder.class);
	private final MonthlyReportNotificationService legacyNotifications = mock(MonthlyReportNotificationService.class);
	private final ObjectMapper mapper = new ObjectMapper();
	private final ValidatorFactory validation = Validation.buildDefaultValidatorFactory();
	private final MonthlyReportPayloadCodec codec = new MonthlyReportPayloadCodec(mapper, validation.getValidator());
	private AnnotationConfigApplicationContext context;
	private EntityManagerFactory emf;
	private JdbcTemplate jdbc;
	private MonthlyReportAdminService admin;
	private MonthlyReportRunWorker worker;
	private MonthlyReportRunRepository runs;
	private MonthlyReportDraftRepository drafts;
	private MonthlyReportPushRepository pushes;
	private TransactionTemplate tx;

	@BeforeAll
	void setup() {
		DriverManagerDataSource source = new DriverManagerDataSource(System.getenv("MONTHLY_REPORT_TEST_JDBC_URL"),
			System.getenv("MONTHLY_REPORT_TEST_DB_USER"), System.getenv("MONTHLY_REPORT_TEST_DB_PASSWORD"));
		jdbc = new JdbcTemplate(source);
		jdbc.execute("CREATE SCHEMA " + schema);
		source.setSchema(schema);
		jdbc.execute("CREATE TABLE member (id bigint PRIMARY KEY)");
		jdbc.execute("CREATE TABLE notification (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY)");
		jdbc.execute("CREATE TABLE notification_delivery (notification_id bigint, member_id bigint, "
			+ "channel varchar(20), status varchar(20), fail_reason varchar(200))");
		jdbc.execute(
			"CREATE TABLE notification_recipient (notification_id bigint, member_id bigint, read_at timestamp)");
		jdbc.execute("CREATE TABLE request_log (member_id bigint, http_method varchar(10), endpoint varchar(500), "
			+ "response_status integer, created_at timestamp)");
		new ResourceDatabasePopulator(
			new ClassPathResource("db/migration/V20261005222045__create_monthly_report_member.sql"),
			new ClassPathResource("db/migration/V20261005222046__add_monthly_report_appearance.sql"),
			new ClassPathResource("db/migration/V20261005222052__monthly_report_assets.sql"),
			new ClassPathResource("db/migration/V20261005222053__monthly_report_push.sql"),
			new ClassPathResource("db/migration/V20261005222054__monthly_report_admin.sql"),
			new ClassPathResource("db/migration/V20261005222055__monthly_report_single_member_run.sql"),
			new ClassPathResource("db/migration/V20261005222056__monthly_report_operations.sql"))
			.execute(source);
		LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
		factory.setDataSource(source);
		factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		factory.setManagedTypes(
			PersistenceManagedTypes.of(MonthlyReportRun.class.getName(), MonthlyReportDraft.class.getName(),
				MonthlyReportPush.class.getName(), MonthlyReportAsset.class.getName(),
				MonthlyReportMember.class.getName(), MonthlyReportNotification.class.getName()));
		factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
		factory.afterPropertiesSet();
		emf = factory.getObject();
		JpaRepositoryFactory repos = new JpaRepositoryFactory(
			SharedEntityManagerCreator.createSharedEntityManager(emf));
		context = new AnnotationConfigApplicationContext();
		context.registerBean(JpaTransactionManager.class, () -> new JpaTransactionManager(emf));
		context.registerBean(MonthlyReportRunRepository.class,
			() -> repos.getRepository(MonthlyReportRunRepository.class));
		context.registerBean(MonthlyReportDraftRepository.class,
			() -> repos.getRepository(MonthlyReportDraftRepository.class));
		context.registerBean(MonthlyReportNotificationRepository.class,
			() -> repos.getRepository(MonthlyReportNotificationRepository.class));
		context.registerBean(MonthlyReportPushRepository.class,
			() -> repos.getRepository(MonthlyReportPushRepository.class));
		context.registerBean(MonthlyReportRepository.class, () -> repos.getRepository(MonthlyReportRepository.class));
		context.registerBean(MonthlyReportPreparation.class, () -> preparation);
		context.registerBean(MonthlyReportTargetReader.class, () -> targets);
		context.registerBean(MonthlyReportSourceHistoryRepository.class, () -> history);
		context.registerBean(MonthlyReportIngestRecoveryService.class, () -> recovery);
		context.registerBean(MemberReader.class, () -> members);
		context.registerBean(NotificationRecorder.class, () -> recorder);
		context.registerBean(MonthlyReportNotificationService.class, () -> legacyNotifications);
		context.registerBean(BreadRecordTagRepository.class, () -> mock(BreadRecordTagRepository.class));
		context.registerBean(ObjectMapper.class, () -> mapper);
		context.registerBean(MonthlyReportPayloadCodec.class, () -> codec);
		context.register(TxConfig.class, MonthlyReportQueryService.class, MonthlyReportAdminService.class,
			MonthlyReportRunWorker.class, MonthlyReportOperationsService.class,
			MonthlyReportAdminPublisher.class, MonthlyReportPublicationService.class);
		context.refresh();
		admin = context.getBean(MonthlyReportAdminService.class);
		worker = context.getBean(MonthlyReportRunWorker.class);
		runs = context.getBean(MonthlyReportRunRepository.class);
		drafts = context.getBean(MonthlyReportDraftRepository.class);
		pushes = context.getBean(MonthlyReportPushRepository.class);
		tx = new TransactionTemplate(context.getBean(JpaTransactionManager.class));
	}

	@BeforeEach
	void fixtures() {
		jdbc.execute(
			"TRUNCATE monthly_report_push, monthly_report_draft, monthly_report_run, "
				+ "monthly_report_member, notification, notification_delivery, notification_recipient, "
				+ "request_log, member CASCADE");
		jdbc.execute("INSERT INTO member VALUES (123), (456)");
		reset(preparation, targets, history, recovery, members, recorder, legacyNotifications);
		when(history.findCoveredFrom()).thenReturn(LocalDateTime.of(2025, 12, 1, 0, 0));
		when(targets.isEligible(anyLong(), eq(MONTH))).thenReturn(true);
		when(targets.nextPage(eq(MONTH), anyLong(), eq(1))).thenAnswer(call -> {
			long after = call.getArgument(1);
			return new SliceImpl<>(after < 123 ? List.of(123L) : after < 456 ? List.of(456L) : List.of(),
				PageRequest.of(0, 1), after < 123);
		});
		when(preparation.prepare(anyLong(), anyString(), anyString()))
			.thenAnswer(call -> prepared(call.getArgument(0)));
		when(members.getMember(anyLong())).thenAnswer(call -> {
			Member member = MonthlyReportFixtures.eligibleMember("현재 이름");
			ReflectionTestUtils.setField(member, "id", call.getArgument(0));
			return member;
		});
		when(recorder.recordFor(any(), any(), any())).thenAnswer(call -> {
			Notification notification = call.getArgument(0);
			Long id = jdbc.queryForObject("INSERT INTO notification DEFAULT VALUES RETURNING id", Long.class);
			ReflectionTestUtils.setField(notification, "id", id);
			return notification;
		});
	}

	private MonthlyReportGenerationService.Prepared prepared(Long memberId) throws Exception {
		ObjectNode payload = (ObjectNode)mapper.readTree(PAYLOAD);
		payload.put("memberId", memberId);
		payload.put("coverImageKey", "monthly-reports/assets/v1/covers/cat/12.png");
		return new MonthlyReportGenerationService.Prepared(codec.read(payload.toString(), memberId, MONTH),
			mapper.createObjectNode().put("fixed", true), Instant.now().minusSeconds(60));
	}

	@AfterAll
	void cleanup() {
		if (context != null) {
			context.close();
		}
		if (emf != null) {
			emf.close();
		}
		if (jdbc != null) {
			jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
		}
		validation.close();
	}

	@Test
	void preparesReviewsAndPublishesSavedPayloadWithOneScheduledNotification() {
		MonthlyReportRun run = admin.create(MONTH, "v1");
		assertThat(admin.create(MONTH, "v1").getId()).isEqualTo(run.getId());
		assertThatThrownBy(() -> admin.create(MONTH, "v2")).isInstanceOf(CustomException.class);
		assertThatThrownBy(() -> admin.approve(MONTH, run.getId(), null)).isInstanceOf(CustomException.class);
		for (int i = 0; i < 3; i++) {
			worker.step(run.getId());
		}
		assertThat(admin.progress(MONTH, run.getId()).run().getState()).isEqualTo(MonthlyReportRun.State.REVIEW);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM monthly_report_member", Integer.class)).isZero();
		verifyNoInteractions(recorder, legacyNotifications);
		assertThat(admin.preview(MONTH, run.getId(), 123L).coverImageKey()).contains("covers/cat/12.png");
		assertThat(admin.preview(MONTH, run.getId(), 123L).nickname()).isEqualTo("현재 이름");
		String original = drafts.findById(new MonthlyReportDraftId(run.getId(), 123L, MONTH)).orElseThrow()
			.getPayload();
		Instant at = Instant.now().plusSeconds(3600);
		admin.approve(MONTH, run.getId(), at);
		reset(preparation);
		for (int i = 0; i < 3; i++) {
			worker.step(run.getId());
		}
		assertThat(admin.progress(MONTH, run.getId()).run().getState()).isEqualTo(MonthlyReportRun.State.COMPLETE);
		assertThat(mapper.<JsonNode>valueToTree(codec.read(
			jdbc.queryForObject("SELECT payload::text FROM monthly_report_member WHERE member_id=123", String.class),
			123L, MONTH)))
			.isEqualTo(mapper.valueToTree(codec.read(original, 123L, MONTH)));
		assertThat(pushes.count()).isEqualTo(2);
		assertThat(pushes.findDue(Instant.now(), PageRequest.of(0, 100))).isEmpty();
		admin.approve(MONTH, run.getId(), at);
		worker.step(run.getId());
		assertThat(pushes.count()).isEqualTo(2);
		verifyNoInteractions(preparation, legacyNotifications);
		verify(recorder, times(2)).recordFor(any(), any(), any());
	}

	@Test
	void retriesOnlyFailedPreparationsAndBlocksApprovalUntilTheySucceed() {
		when(preparation.prepare(123L, MONTH, "v1"))
			.thenThrow(new CustomException(MonthlyReportErrorCode.ASSET_MISSING));
		MonthlyReportRun run = admin.create(MONTH, "v1");
		for (int i = 0; i < 3; i++) {
			worker.step(run.getId());
		}
		assertThat(admin.progress(MONTH, run.getId()).counts().get(MonthlyReportDraft.State.FAILED)).isEqualTo(1);
		assertThatThrownBy(() -> admin.approve(MONTH, run.getId(), null)).isInstanceOf(CustomException.class);
		reset(preparation);
		when(preparation.prepare(anyLong(), anyString(), anyString()))
			.thenAnswer(call -> prepared(call.getArgument(0)));
		admin.retry(MONTH, run.getId());
		worker.step(run.getId());
		worker.step(run.getId());
		verify(preparation).prepare(123L, MONTH, "v1");
		verifyNoMoreInteractions(preparation);
		admin.approve(MONTH, run.getId(), null);
		for (int i = 0; i < 3; i++) {
			worker.step(run.getId());
		}
		assertThat(pushes.count()).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM notification", Integer.class)).isEqualTo(2);
	}

	@Test
	void insufficientHistoryBlocksBeforeReadingMembersAndDoesNotConsumeAttempts() {
		when(history.findCoveredFrom()).thenReturn(LocalDateTime.of(2026, 1, 1, 0, 0));
		MonthlyReportRun run = admin.create(MONTH, "v1");
		worker.step(run.getId());
		assertThat(admin.progress(MONTH, run.getId()).run().getErrorCode()).isEqualTo("MONTHLY_REPORT5032");
		assertThat(drafts.count()).isZero();
		verifyNoInteractions(preparation, targets, recovery);
		when(history.findCoveredFrom()).thenReturn(LocalDateTime.of(2025, 12, 1, 0, 0));
		admin.retry(MONTH, run.getId());
		worker.step(run.getId());
		assertThat(drafts.count()).isEqualTo(1);
	}

	@Test
	void publicationFailureRollsBackSnapshotAndOutboxAndRetriesWithoutRecalculation() {
		MonthlyReportRun run = admin.create(MONTH, "v1");
		for (int i = 0; i < 3; i++) {
			worker.step(run.getId());
		}
		doThrow(new IllegalStateException("simulated notification error")).when(recorder).recordFor(any(), any(),
			any());
		admin.approve(MONTH, run.getId(), Instant.now().plusSeconds(3600));
		for (int i = 0; i < 3; i++) {
			worker.step(run.getId());
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM monthly_report_member", Integer.class)).isZero();
		assertThat(pushes.count()).isZero();
		assertThat(admin.progress(MONTH, run.getId()).counts().get(MonthlyReportDraft.State.PUBLISH_FAILED))
			.isEqualTo(2);
		reset(preparation, recorder);
		when(recorder.recordFor(any(), any(), any())).thenAnswer(call -> {
			Notification notification = call.getArgument(0);
			ReflectionTestUtils.setField(notification, "id",
				jdbc.queryForObject("INSERT INTO notification DEFAULT VALUES RETURNING id", Long.class));
			return notification;
		});
		admin.retry(MONTH, run.getId());
		for (int i = 0; i < 3; i++) {
			worker.step(run.getId());
		}
		assertThat(pushes.count()).isEqualTo(2);
		verifyNoInteractions(preparation);
	}

	@Test
	void noLongerEligibleMembersAreSkippedBeforePreparationAndPublication() {
		when(targets.isEligible(123L, MONTH)).thenReturn(false);
		MonthlyReportRun run = admin.create(MONTH, "v1");
		for (int index = 0; index < 3; index++) {
			worker.step(run.getId());
		}
		verify(preparation).prepare(456L, MONTH, "v1");
		verifyNoMoreInteractions(preparation);
		when(targets.isEligible(456L, MONTH)).thenReturn(false);
		admin.approve(MONTH, run.getId(), null);
		worker.step(run.getId());
		worker.step(run.getId());
		assertThat(admin.progress(MONTH, run.getId()).counts().get(MonthlyReportDraft.State.SKIPPED)).isEqualTo(2);
		verifyNoInteractions(recorder);
	}

	@Test
	void singleMemberRunPublishesOnlySelectedMemberAndKeepsAllMemberDraftsSeparate() {
		MonthlyReportRun single = admin.create(MONTH, "v1", 123L);
		MonthlyReportRun all = admin.create(MONTH, "v1");
		assertThat(single.getTargetMemberId()).isEqualTo(123L);
		assertThat(single.getId()).isNotEqualTo(all.getId());
		assertThat(admin.create(MONTH, "v1", 123L).getId()).isEqualTo(single.getId());
		worker.step(single.getId());
		worker.step(single.getId());
		verify(targets, never()).nextPage(anyString(), anyLong(), anyInt());
		assertThat(admin.items(MONTH, single.getId(), 0, 20).content()).hasSize(1);
		assertThatThrownBy(() -> admin.preview(MONTH, single.getId(), 456L)).isInstanceOf(CustomException.class);
		for (int i = 0; i < 3; i++) {
			worker.step(all.getId());
		}
		assertThat(admin.items(MONTH, all.getId(), 0, 20).content()).hasSize(2);
		assertThat(drafts.count()).isEqualTo(3);
		admin.approve(MONTH, single.getId(), Instant.now().plusSeconds(3600));
		worker.step(single.getId());
		worker.step(single.getId());
		assertThat(jdbc.queryForList("SELECT member_id FROM monthly_report_member", Long.class)).containsExactly(123L);
		assertThat(pushes.findAll()).singleElement().satisfies(push -> assertThat(push.getMemberId()).isEqualTo(123L));
		admin.approve(MONTH, all.getId(), Instant.now().plusSeconds(3600));
		for (int i = 0; i < 3; i++) {
			worker.step(all.getId());
		}
		assertThat(pushes.count()).isEqualTo(2);
		verify(recorder, times(2)).recordFor(any(), any(), any());
	}

	@Test
	void singleMemberMustBeEligibleAndVersionCannotChange() {
		when(targets.isEligible(999L, MONTH)).thenReturn(false);
		assertThatThrownBy(() -> admin.create(MONTH, "v1", 999L)).isInstanceOf(CustomException.class);
		assertThat(runs.count()).isZero();
		admin.create(MONTH, "v1", 123L);
		assertThatThrownBy(() -> admin.create(MONTH, "v2", 123L)).isInstanceOf(CustomException.class);
	}

	@Test
	void operationsUseSuccessfulRequestLogsAndExistingNotificationsIndependently() {
		MonthlyReportRun run = admin.create(MONTH, "v1", 123L);
		worker.step(run.getId());
		worker.step(run.getId());
		MonthlyReportOperationsService operations = context.getBean(MonthlyReportOperationsService.class);
		assertThat(operations.status(MONTH, run.getId(), 0, 20).published()).isZero();
		admin.approve(MONTH, run.getId(), Instant.now().plusSeconds(3600));
		worker.step(run.getId());
		worker.step(run.getId());
		MonthlyReportOperationsService.Operations before = operations.status(MONTH, run.getId(), 0, 20);
		assertThat(before.published()).isEqualTo(1);
		assertThat(before.viewed()).isZero();
		assertThat(before.pushCounts()).containsEntry("PENDING", 1L);
		assertThat(before.members().content()).singleElement().satisfies(status -> {
			assertThat(status.getSendAt()).isAfter(Instant.now());
			assertThat(status.getPublishedAt()).isNotNull();
			assertThat(status.getFirstViewedAt()).isNull();
		});
		admin.preview(MONTH, run.getId(), 123L);
		Instant first = Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.SECONDS);
		String endpoint = "/reports/monthly/" + MONTH + "/me";
		insertRequestLog(123L, "GET", endpoint, 404, first);
		insertRequestLog(123L, "POST", endpoint, 200, first);
		insertRequestLog(456L, "GET", endpoint, 200, first);
		insertRequestLog(123L, "GET", "/reports/monthly/2025-01/me", 200, first);
		insertRequestLog(123L, "GET", "/api/admin/monthly-reports/" + MONTH + "/preview/123", 200, first);
		insertRequestLog(123L, "GET", endpoint, 200, first.minusSeconds(3600));
		Long notificationId = pushes.findAll().get(0).getNotificationId();
		jdbc.update("INSERT INTO notification_recipient VALUES (?, 123, TIMESTAMP '2026-10-05 10:00:00')",
			notificationId);
		assertThat(operations.status(MONTH, run.getId(), 0, 20).viewed()).isZero();
		assertThat(operations.status(MONTH, run.getId(), 0, 20).notificationOpened()).isEqualTo(1);
		insertRequestLog(123L, "GET", endpoint, 200, first);
		insertRequestLog(123L, "GET", endpoint, 200, first.plusSeconds(60));
		assertThat(operations.status(MONTH, run.getId(), 0, 20).viewed()).isEqualTo(1);
		assertThat(operations.status(MONTH, run.getId(), 0, 20).members().content().get(0).getFirstViewedAt())
			.isEqualTo(first);
		jdbc.update("INSERT INTO notification_delivery VALUES (?, 123, 'PUSH', 'SENT', NULL)", notificationId);
		assertThat(operations.status(MONTH, run.getId(), 0, 20).pushCounts()).containsEntry("SENT", 1L);
	}

	private void insertRequestLog(Long memberId, String method, String endpoint, int status, Instant at) {
		jdbc.update("INSERT INTO request_log VALUES (?, ?, ?, ?, ?)", memberId, method, endpoint, status,
			LocalDateTime.ofInstant(at, ZoneId.of("Asia/Seoul")));
	}

	@Test
	void manualPushRetryOnlyQueuesExhaustedFailureForMemberOfRun() {
		MonthlyReportRun run = admin.create(MONTH, "v1", 123L);
		worker.step(run.getId());
		worker.step(run.getId());
		admin.approve(MONTH, run.getId(), Instant.now().plusSeconds(3600));
		worker.step(run.getId());
		MonthlyReportOperationsService operations = context.getBean(MonthlyReportOperationsService.class);
		assertThatThrownBy(() -> operations.retryPush(MONTH, run.getId(), 123L)).isInstanceOf(CustomException.class);
		assertThatThrownBy(() -> operations.retryPush(MONTH, run.getId(), 456L)).isInstanceOf(CustomException.class);
		Long notificationId = pushes.findAll().get(0).getNotificationId();
		tx.executeWithoutResult(status -> {
			MonthlyReportPush push = pushes.lock(notificationId).orElseThrow();
			for (int i = 0; i < 5; i++) {
				push.begin();
				push.fail();
			}
		});
		assertThat(operations.status(MONTH, run.getId(), 0, 20).pushCounts()).containsEntry("FAILED", 1L);
		jdbc.update("INSERT INTO notification_delivery VALUES (?, 123, 'PUSH', 'SENT', NULL)", notificationId);
		assertThatThrownBy(() -> operations.retryPush(MONTH, run.getId(), 123L)).isInstanceOf(CustomException.class);
		jdbc.update("DELETE FROM notification_delivery WHERE notification_id = ?", notificationId);
		operations.retryPush(MONTH, run.getId(), 123L);
		assertThat(pushes.findDue(Instant.now(), PageRequest.of(0, 10))).containsExactly(notificationId);
		assertThat(operations.status(MONTH, run.getId(), 0, 20).members().content()).singleElement()
			.satisfies(status -> {
				assertThat(status.getTotalAttempts()).isEqualTo(5);
				assertThat(status.getManualRetries()).isEqualTo(1);
			});
		assertThatThrownBy(() -> operations.retryPush(MONTH, run.getId(), 123L)).isInstanceOf(CustomException.class);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM notification", Integer.class)).isEqualTo(1);
	}

	@Test
	void automaticRegistrationKeepsExistingVersionAndStopsAtReview() {
		admin.prepareAutomatically(MONTH, "v1");
		admin.prepareAutomatically(MONTH, "v2");
		MonthlyReportRun run = admin.list(MONTH, 0, 20).content().get(0);
		assertThat(run.getAssetVersion()).isEqualTo("v1");
		assertThat(run.getTargetMemberId()).isNull();
		assertThat(runs.count()).isEqualTo(1);
		assertThat(admin.create(MONTH, "v1").getId()).isEqualTo(run.getId());
		admin.create(MONTH, "v1", 123L);
		assertThat(admin.list(MONTH, 0, 1).hasNext()).isTrue();
		assertThat(admin.list(MONTH, 1, 1).content()).singleElement()
			.satisfies(item -> assertThat(item.getId()).isEqualTo(run.getId()));
		assertThatThrownBy(() -> admin.list(MONTH, -1, 20)).isInstanceOf(CustomException.class);
		assertThatThrownBy(() -> admin.list(MONTH, 0, 101)).isInstanceOf(CustomException.class);
		for (int i = 0; i < 3; i++) {
			worker.step(run.getId());
		}
		assertThat(admin.progress(MONTH, run.getId()).run().getState()).isEqualTo(MonthlyReportRun.State.REVIEW);
		admin.prepareAutomatically(MONTH, "v2");
		assertThat(admin.progress(MONTH, run.getId()).run().getState()).isEqualTo(MonthlyReportRun.State.REVIEW);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM monthly_report_member", Integer.class)).isZero();
		assertThat(pushes.count()).isZero();
		verifyNoInteractions(recorder, legacyNotifications);
	}

	@Test
	void concurrentRegistrationReturnsSameRunAndLockedRunIsSkipped() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<UUID> first = pool.submit(() -> admin.create(MONTH, "v1").getId());
			Future<UUID> second = pool.submit(() -> admin.create(MONTH, "v1").getId());
			UUID id = first.get(5, TimeUnit.SECONDS);
			assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(id);
			tx.executeWithoutResult(status -> {
				runs.lock(id).orElseThrow();
				try {
					pool.submit(() -> worker.step(id)).get(5, TimeUnit.SECONDS);
				} catch (Exception error) {
					throw new IllegalStateException(error);
				}
			});
			verifyNoInteractions(preparation);
		} finally {
			pool.shutdownNow();
		}
	}

	@Configuration
	@EnableTransactionManagement
	static class TxConfig {
		// 서비스 트랜잭션 경계를 실제 DB에서 검증한다.
	}
}

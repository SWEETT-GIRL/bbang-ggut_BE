package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.repository.NotificationDeliveryRepository;
import sweetgirl.croissant.alarm.repository.NotificationRepository;
import sweetgirl.croissant.alarm.service.NotificationRecorder;
import sweetgirl.croissant.alarm.service.PushDispatcher;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportAdminRequests.Asset;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportOperationsResponse;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportRunResponse;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportAsset;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportDraft;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportDraftId;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportPush;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportRun;
import sweetgirl.croissant.monthlyreport.admin.repository.MonthlyReportAssetRepository;
import sweetgirl.croissant.monthlyreport.admin.repository.MonthlyReportDraftRepository;
import sweetgirl.croissant.monthlyreport.admin.repository.MonthlyReportPushRepository;
import sweetgirl.croissant.monthlyreport.admin.repository.MonthlyReportRunRepository;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAdminCommandService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAdminPublisher;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAdminQueryService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAssetCommandService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAssetQueryService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAssetStore;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAssetUploadService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportOperationsCommandService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportOperationsQueryService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportPreparation;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportPushCommandService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportPushWorker;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportRunWorker;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMember;
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
import sweetgirl.croissant.world.repository.WorldCharacterRepository;
import sweetgirl.croissant.world.repository.WorldPlanetRepository;

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
	private MonthlyReportAdminCommandService admin;
	private MonthlyReportAdminQueryService queries;
	private MonthlyReportRunWorker worker;
	private MonthlyReportRunRepository runs;
	private MonthlyReportDraftRepository drafts;
	private MonthlyReportPushRepository pushes;
	private TransactionTemplate tx;
	private final MemberRepository pushMembers = mock(MemberRepository.class);
	private final NotificationRepository pushNotifications = mock(NotificationRepository.class);
	private final NotificationDeliveryRepository pushDeliveries = mock(NotificationDeliveryRepository.class);
	private final PushDispatcher pushDispatcher = mock(PushDispatcher.class);

	@BeforeAll
	void setup() throws Exception {
		DriverManagerDataSource source = new DriverManagerDataSource(System.getenv("MONTHLY_REPORT_TEST_JDBC_URL"),
			System.getenv("MONTHLY_REPORT_TEST_DB_USER"), System.getenv("MONTHLY_REPORT_TEST_DB_PASSWORD"));
		jdbc = new JdbcTemplate(source);
		jdbc.execute("CREATE SCHEMA " + schema);
		source.setSchema(schema);
		jdbc.execute("CREATE TABLE member (id bigint PRIMARY KEY)");
		jdbc.execute("CREATE TABLE notification (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY)");
		jdbc.execute("CREATE TABLE notification_delivery (notification_id bigint, member_id bigint, "
			+ "channel varchar(20), status varchar(20), fail_reason varchar(200), sent_at timestamp, "
			+ "UNIQUE(notification_id, member_id, channel))");
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
			new ClassPathResource("db/migration/V20261005222056__monthly_report_operations.sql"),
			new ClassPathResource("db/migration/V20261006055838__add_monthly_report_push_lease.sql"))
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
		context.registerBean(MemberRepository.class, () -> pushMembers);
		context.registerBean(NotificationRepository.class, () -> pushNotifications);
		context.registerBean(NotificationDeliveryRepository.class, () -> pushDeliveries);
		context.registerBean(PushDispatcher.class, () -> pushDispatcher);
		context.register(MonthlyReportPushCommandService.class, MonthlyReportPushWorker.class);
		context.registerBean(MonthlyReportAssetRepository.class,
			() -> repos.getRepository(MonthlyReportAssetRepository.class));
		context.registerBean(WorldCharacterRepository.class, () -> mock(WorldCharacterRepository.class));
		context.registerBean(WorldPlanetRepository.class, () -> mock(WorldPlanetRepository.class));
		context.registerBean(MonthlyReportAssetStore.class, () -> mock(MonthlyReportAssetStore.class));
		context.register(MonthlyReportAssetQueryService.class, MonthlyReportAssetCommandService.class,
			MonthlyReportAssetUploadService.class);
		context.register(TxConfig.class, MonthlyReportQueryService.class, MonthlyReportAdminCommandService.class,
			MonthlyReportAdminQueryService.class, MonthlyReportOperationsCommandService.class,
			MonthlyReportRunWorker.class, MonthlyReportOperationsQueryService.class,
			MonthlyReportAdminPublisher.class, MonthlyReportPublicationService.class);
		context.refresh();
		admin = context.getBean(MonthlyReportAdminCommandService.class);
		queries = context.getBean(MonthlyReportAdminQueryService.class);
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
		reset(preparation, targets, history, recovery, members, recorder, legacyNotifications,
			pushMembers, pushNotifications, pushDeliveries, pushDispatcher);
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
	void assetStorageValidationRunsOutsideDatabaseTransaction() {
		when(context.getBean(WorldCharacterRepository.class).existsById("cat")).thenReturn(true);
		String key = "monthly-reports/assets/tx-check/covers/cat/09.png";
		MonthlyReportAssetStore assetStore = context.getBean(MonthlyReportAssetStore.class);
		doAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return null;
		}).when(assetStore).validateStoredAsset(key, "image/png");
		Asset request = new Asset("tx-check", MonthlyReportAsset.Kind.COVER, "cat", "", "09", "image/png", key);
		assertThat(context.getBean(MonthlyReportAssetUploadService.class).registerReportAsset(request).imageKey())
			.isEqualTo(key);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM monthly_report_asset WHERE image_key = ?",
			Integer.class, key)).isEqualTo(1);
		verify(assetStore).validateStoredAsset(key, "image/png");
	}

	@Test
	void preparesReviewsAndPublishesSavedPayloadWithOneScheduledNotification() {
		MonthlyReportRunResponse run = admin.createReportRun(MONTH, "v1");
		assertThat(admin.createReportRun(MONTH, "v1").id()).isEqualTo(run.id());
		assertThatThrownBy(() -> admin.createReportRun(MONTH, "v2")).isInstanceOf(CustomException.class);
		assertThatThrownBy(() -> admin.approveReportPublication(MONTH, run.id(), null))
			.isInstanceOf(CustomException.class);
		for (int i = 0; i < 3; i++) {
			worker.processReportRunStep(run.id());
		}
		assertThat(queries.getReportRunProgress(MONTH, run.id()).run().state())
			.isEqualTo(MonthlyReportRun.State.REVIEW);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM monthly_report_member", Integer.class)).isZero();
		verifyNoInteractions(recorder, legacyNotifications);
		assertThat(queries.getReportPreview(MONTH, run.id(), 123L).coverImageKey()).contains("covers/cat/12.png");
		assertThat(queries.getReportPreview(MONTH, run.id(), 123L).nickname()).isEqualTo("현재 이름");
		String original = drafts.findById(new MonthlyReportDraftId(run.id(), 123L, MONTH)).orElseThrow()
			.getPayload();
		Instant at = Instant.now().plusSeconds(3600);
		admin.approveReportPublication(MONTH, run.id(), at);
		reset(preparation);
		for (int i = 0; i < 3; i++) {
			worker.processReportRunStep(run.id());
		}
		assertThat(queries.getReportRunProgress(MONTH, run.id()).run().state())
			.isEqualTo(MonthlyReportRun.State.COMPLETE);
		assertThat(mapper.<JsonNode>valueToTree(codec.read(
			jdbc.queryForObject("SELECT payload::text FROM monthly_report_member WHERE member_id=123", String.class),
			123L, MONTH)))
			.isEqualTo(mapper.valueToTree(codec.read(original, 123L, MONTH)));
		assertThat(pushes.count()).isEqualTo(2);
		assertThat(pushes.findDue(Instant.now(), PageRequest.of(0, 100))).isEmpty();
		admin.approveReportPublication(MONTH, run.id(), at);
		worker.processReportRunStep(run.id());
		assertThat(pushes.count()).isEqualTo(2);
		verifyNoInteractions(preparation, legacyNotifications);
		verify(recorder, times(2)).recordFor(any(), any(), any());
	}

	@Test
	void retriesOnlyFailedPreparationsAndBlocksApprovalUntilTheySucceed() {
		when(preparation.prepare(123L, MONTH, "v1"))
			.thenThrow(new CustomException(MonthlyReportErrorCode.ASSET_MISSING));
		MonthlyReportRunResponse run = admin.createReportRun(MONTH, "v1");
		for (int i = 0; i < 3; i++) {
			worker.processReportRunStep(run.id());
		}
		assertThat(queries.getReportRunProgress(MONTH, run.id()).counts().get(MonthlyReportDraft.State.FAILED))
			.isEqualTo(1);
		assertThatThrownBy(() -> admin.approveReportPublication(MONTH, run.id(), null))
			.isInstanceOf(CustomException.class);
		reset(preparation);
		when(preparation.prepare(anyLong(), anyString(), anyString()))
			.thenAnswer(call -> prepared(call.getArgument(0)));
		admin.retryReportRun(MONTH, run.id());
		worker.processReportRunStep(run.id());
		worker.processReportRunStep(run.id());
		verify(preparation).prepare(123L, MONTH, "v1");
		verifyNoMoreInteractions(preparation);
		admin.approveReportPublication(MONTH, run.id(), null);
		for (int i = 0; i < 3; i++) {
			worker.processReportRunStep(run.id());
		}
		assertThat(pushes.count()).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM notification", Integer.class)).isEqualTo(2);
	}

	@Test
	void insufficientHistoryBlocksBeforeReadingMembersAndDoesNotConsumeAttempts() {
		when(history.findCoveredFrom()).thenReturn(LocalDateTime.of(2026, 1, 1, 0, 0));
		MonthlyReportRunResponse run = admin.createReportRun(MONTH, "v1");
		worker.processReportRunStep(run.id());
		assertThat(queries.getReportRunProgress(MONTH, run.id()).run().errorCode()).isEqualTo("MONTHLY_REPORT5032");
		assertThat(drafts.count()).isZero();
		verifyNoInteractions(preparation, targets, recovery);
		when(history.findCoveredFrom()).thenReturn(LocalDateTime.of(2025, 12, 1, 0, 0));
		admin.retryReportRun(MONTH, run.id());
		worker.processReportRunStep(run.id());
		assertThat(drafts.count()).isEqualTo(1);
	}

	@Test
	void publicationFailureRollsBackSnapshotAndOutboxAndRetriesWithoutRecalculation() {
		MonthlyReportRunResponse run = admin.createReportRun(MONTH, "v1");
		for (int i = 0; i < 3; i++) {
			worker.processReportRunStep(run.id());
		}
		doThrow(new IllegalStateException("simulated notification error")).when(recorder).recordFor(any(), any(),
			any());
		admin.approveReportPublication(MONTH, run.id(), Instant.now().plusSeconds(3600));
		for (int i = 0; i < 3; i++) {
			worker.processReportRunStep(run.id());
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM monthly_report_member", Integer.class)).isZero();
		assertThat(pushes.count()).isZero();
		assertThat(queries.getReportRunProgress(MONTH, run.id()).counts().get(MonthlyReportDraft.State.PUBLISH_FAILED))
			.isEqualTo(2);
		reset(preparation, recorder);
		when(recorder.recordFor(any(), any(), any())).thenAnswer(call -> {
			Notification notification = call.getArgument(0);
			ReflectionTestUtils.setField(notification, "id",
				jdbc.queryForObject("INSERT INTO notification DEFAULT VALUES RETURNING id", Long.class));
			return notification;
		});
		admin.retryReportRun(MONTH, run.id());
		for (int i = 0; i < 3; i++) {
			worker.processReportRunStep(run.id());
		}
		assertThat(pushes.count()).isEqualTo(2);
		verifyNoInteractions(preparation);
	}

	@Test
	void noLongerEligibleMembersAreSkippedBeforePreparationAndPublication() {
		when(targets.isEligible(123L, MONTH)).thenReturn(false);
		MonthlyReportRunResponse run = admin.createReportRun(MONTH, "v1");
		for (int index = 0; index < 3; index++) {
			worker.processReportRunStep(run.id());
		}
		verify(preparation).prepare(456L, MONTH, "v1");
		verifyNoMoreInteractions(preparation);
		when(targets.isEligible(456L, MONTH)).thenReturn(false);
		admin.approveReportPublication(MONTH, run.id(), null);
		worker.processReportRunStep(run.id());
		worker.processReportRunStep(run.id());
		assertThat(queries.getReportRunProgress(MONTH, run.id()).counts().get(MonthlyReportDraft.State.SKIPPED))
			.isEqualTo(2);
		verifyNoInteractions(recorder);
	}

	@Test
	void singleMemberRunPublishesOnlySelectedMemberAndKeepsAllMemberDraftsSeparate() {
		MonthlyReportRunResponse single = admin.createReportRun(MONTH, "v1", 123L);
		MonthlyReportRunResponse all = admin.createReportRun(MONTH, "v1");
		assertThat(single.targetMemberId()).isEqualTo(123L);
		assertThat(single.id()).isNotEqualTo(all.id());
		assertThat(admin.createReportRun(MONTH, "v1", 123L).id()).isEqualTo(single.id());
		worker.processReportRunStep(single.id());
		worker.processReportRunStep(single.id());
		verify(targets, never()).nextPage(anyString(), anyLong(), anyInt());
		assertThat(queries.getReportRunMembers(MONTH, single.id(), 0, 20).content()).hasSize(1);
		assertThatThrownBy(() -> queries.getReportPreview(MONTH, single.id(), 456L))
			.isInstanceOf(CustomException.class);
		for (int i = 0; i < 3; i++) {
			worker.processReportRunStep(all.id());
		}
		assertThat(queries.getReportRunMembers(MONTH, all.id(), 0, 20).content()).hasSize(2);
		assertThat(drafts.count()).isEqualTo(3);
		admin.approveReportPublication(MONTH, single.id(), Instant.now().plusSeconds(3600));
		worker.processReportRunStep(single.id());
		worker.processReportRunStep(single.id());
		assertThat(jdbc.queryForList("SELECT member_id FROM monthly_report_member", Long.class)).containsExactly(123L);
		assertThat(pushes.findAll()).singleElement().satisfies(push -> assertThat(push.getMemberId()).isEqualTo(123L));
		admin.approveReportPublication(MONTH, all.id(), Instant.now().plusSeconds(3600));
		for (int i = 0; i < 3; i++) {
			worker.processReportRunStep(all.id());
		}
		assertThat(pushes.count()).isEqualTo(2);
		verify(recorder, times(2)).recordFor(any(), any(), any());
	}

	@Test
	void singleMemberMustBeEligibleAndVersionCannotChange() {
		when(targets.isEligible(999L, MONTH)).thenReturn(false);
		assertThatThrownBy(() -> admin.createReportRun(MONTH, "v1", 999L)).isInstanceOf(CustomException.class);
		assertThat(runs.count()).isZero();
		admin.createReportRun(MONTH, "v1", 123L);
		assertThatThrownBy(() -> admin.createReportRun(MONTH, "v2", 123L)).isInstanceOf(CustomException.class);
	}

	@Test
	void operationsUseSuccessfulRequestLogsAndExistingNotificationsIndependently() {
		MonthlyReportRunResponse run = admin.createReportRun(MONTH, "v1", 123L);
		worker.processReportRunStep(run.id());
		worker.processReportRunStep(run.id());
		MonthlyReportOperationsQueryService operations = context.getBean(MonthlyReportOperationsQueryService.class);
		assertThat(operations.getReportOperations(MONTH, run.id(), 0, 20).published()).isZero();
		admin.approveReportPublication(MONTH, run.id(), Instant.now().plusSeconds(3600));
		worker.processReportRunStep(run.id());
		worker.processReportRunStep(run.id());
		MonthlyReportOperationsResponse before = operations.getReportOperations(MONTH, run.id(), 0, 20);
		assertThat(before.published()).isEqualTo(1);
		assertThat(before.viewed()).isZero();
		assertThat(before.pushCounts()).containsEntry("PENDING", 1L);
		assertThat(before.members().content()).singleElement().satisfies(status -> {
			assertThat(status.sendAt()).isAfter(Instant.now());
			assertThat(status.publishedAt()).isNotNull();
			assertThat(status.firstViewedAt()).isNull();
		});
		queries.getReportPreview(MONTH, run.id(), 123L);
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
		assertThat(operations.getReportOperations(MONTH, run.id(), 0, 20).viewed()).isZero();
		assertThat(operations.getReportOperations(MONTH, run.id(), 0, 20).notificationOpened()).isEqualTo(1);
		insertRequestLog(123L, "GET", endpoint, 200, first);
		insertRequestLog(123L, "GET", endpoint, 200, first.plusSeconds(60));
		assertThat(operations.getReportOperations(MONTH, run.id(), 0, 20).viewed()).isEqualTo(1);
		assertThat(
			operations.getReportOperations(MONTH, run.id(), 0, 20).members().content().get(0).firstViewedAt())
			.isEqualTo(first);
		jdbc.update(
			"INSERT INTO notification_delivery (notification_id, member_id, channel, status, fail_reason) "
				+ "VALUES (?, 123, 'PUSH', 'SENT', NULL)",
			notificationId);
		assertThat(operations.getReportOperations(MONTH, run.id(), 0, 20).pushCounts()).containsEntry("SENT", 1L);
	}

	private void insertRequestLog(Long memberId, String method, String endpoint, int status, Instant at) {
		jdbc.update("INSERT INTO request_log VALUES (?, ?, ?, ?, ?)", memberId, method, endpoint, status,
			LocalDateTime.ofInstant(at, ZoneId.of("Asia/Seoul")));
	}

	@Test
	void manualPushRetryOnlyQueuesExhaustedFailureForMemberOfRun() {
		MonthlyReportRunResponse run = admin.createReportRun(MONTH, "v1", 123L);
		worker.processReportRunStep(run.id());
		worker.processReportRunStep(run.id());
		admin.approveReportPublication(MONTH, run.id(), Instant.now().plusSeconds(3600));
		worker.processReportRunStep(run.id());
		MonthlyReportOperationsQueryService operations = context.getBean(MonthlyReportOperationsQueryService.class);
		assertThatThrownBy(() -> context.getBean(MonthlyReportOperationsCommandService.class)
			.retryFailedReportPush(MONTH, run.id(), 123L))
			.isInstanceOf(CustomException.class);
		assertThatThrownBy(() -> context.getBean(MonthlyReportOperationsCommandService.class)
			.retryFailedReportPush(MONTH, run.id(), 456L))
			.isInstanceOf(CustomException.class);
		Long notificationId = pushes.findAll().get(0).getNotificationId();
		tx.executeWithoutResult(status -> {
			MonthlyReportPush push = pushes.lock(notificationId).orElseThrow();
			for (int i = 0; i < 5; i++) {
				push.begin();
				push.fail();
			}
		});
		assertThat(operations.getReportOperations(MONTH, run.id(), 0, 20).pushCounts()).containsEntry("FAILED", 1L);
		jdbc.update(
			"INSERT INTO notification_delivery (notification_id, member_id, channel, status, fail_reason) "
				+ "VALUES (?, 123, 'PUSH', 'SENT', NULL)",
			notificationId);
		assertThatThrownBy(() -> context.getBean(MonthlyReportOperationsCommandService.class)
			.retryFailedReportPush(MONTH, run.id(), 123L))
			.isInstanceOf(CustomException.class);
		jdbc.update("DELETE FROM notification_delivery WHERE notification_id = ?", notificationId);
		context.getBean(MonthlyReportOperationsCommandService.class).retryFailedReportPush(MONTH, run.id(), 123L);
		assertThat(pushes.findDue(Instant.now(), PageRequest.of(0, 10))).containsExactly(notificationId);
		assertThat(operations.getReportOperations(MONTH, run.id(), 0, 20).members().content()).singleElement()
			.satisfies(status -> {
				assertThat(status.totalAttempts()).isEqualTo(5);
				assertThat(status.manualRetries()).isEqualTo(1);
			});
		assertThatThrownBy(() -> context.getBean(MonthlyReportOperationsCommandService.class)
			.retryFailedReportPush(MONTH, run.id(), 123L))
			.isInstanceOf(CustomException.class);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM notification", Integer.class)).isEqualTo(1);
	}

	@Test
	void automaticRegistrationKeepsExistingVersionAndStopsAtReview() {
		admin.registerAutomaticReportRun(MONTH, "v1");
		admin.registerAutomaticReportRun(MONTH, "v2");
		MonthlyReportRunResponse run = queries.getReportRuns(MONTH, 0, 20).content().get(0);
		assertThat(run.assetVersion()).isEqualTo("v1");
		assertThat(run.targetMemberId()).isNull();
		assertThat(runs.count()).isEqualTo(1);
		assertThat(admin.createReportRun(MONTH, "v1").id()).isEqualTo(run.id());
		admin.createReportRun(MONTH, "v1", 123L);
		assertThat(queries.getReportRuns(MONTH, 0, 1).hasNext()).isTrue();
		assertThat(queries.getReportRuns(MONTH, 1, 1).content()).singleElement()
			.satisfies(item -> assertThat(item.id()).isEqualTo(run.id()));
		assertThatThrownBy(() -> queries.getReportRuns(MONTH, -1, 20)).isInstanceOf(CustomException.class);
		assertThatThrownBy(() -> queries.getReportRuns(MONTH, 0, 101)).isInstanceOf(CustomException.class);
		for (int i = 0; i < 3; i++) {
			worker.processReportRunStep(run.id());
		}
		assertThat(queries.getReportRunProgress(MONTH, run.id()).run().state())
			.isEqualTo(MonthlyReportRun.State.REVIEW);
		admin.registerAutomaticReportRun(MONTH, "v2");
		assertThat(queries.getReportRunProgress(MONTH, run.id()).run().state())
			.isEqualTo(MonthlyReportRun.State.REVIEW);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM monthly_report_member", Integer.class)).isZero();
		assertThat(pushes.count()).isZero();
		verifyNoInteractions(recorder, legacyNotifications);
	}

	@Test
	void pushClaimCommitsBeforeDispatchAndCannotBeClaimedAgain() {
		Long notificationId = jdbc.queryForObject("INSERT INTO notification DEFAULT VALUES RETURNING id", Long.class);
		tx.executeWithoutResult(status -> pushes.save(MonthlyReportPush.schedule(notificationId, 123L,
			Instant.now().minusSeconds(1))));
		when(pushMembers.findById(123L)).thenReturn(Optional.of(MonthlyReportFixtures.eligibleMember("테스트")));
		when(pushNotifications.findById(notificationId)).thenReturn(Optional.of(mock(Notification.class)));
		when(pushDispatcher.dispatchWithResult(any(), any(), any())).thenAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			assertThat(pushes.findById(notificationId).orElseThrow().getLeaseToken()).isNotNull();
			assertThat(context.getBean(MonthlyReportPushCommandService.class).claimScheduledPush(notificationId))
				.isEmpty();
			return PushDispatcher.Result.SENT;
		});
		context.getBean(MonthlyReportPushWorker.class).sendScheduledReportPush(notificationId);
		MonthlyReportPush completed = pushes.findById(notificationId).orElseThrow();
		assertThat(completed.isDone()).isTrue();
		assertThat(completed.getLeaseToken()).isNull();
		assertThat(completed.getAttempts()).isEqualTo(1);
	}

	@Test
	void concurrentDeliveryResultsNeverDowngradeSuccess() throws Exception {
		String sql = NotificationDeliveryRepository.class.getMethod("recordPushResult", Long.class, Long.class,
			String.class, String.class, LocalDateTime.class)
			.getAnnotation(Query.class).value();
		NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(
			jdbc);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<Integer>> writes = pool.invokeAll(List.of(
				() -> namedJdbc.update(sql, Map.of("notificationId", 1L, "memberId", 123L,
					"status", "FAILED", "failReason", "FCM_SEND_FAILED", "now", LocalDateTime.now())),
				() -> namedJdbc.update(sql, Map.of("notificationId", 1L, "memberId", 123L,
					"status", "SENT", "failReason", "", "now", LocalDateTime.now()))));
			for (Future<Integer> write : writes) {
				write.get(10, TimeUnit.SECONDS);
			}
			assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_delivery", Integer.class)).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT status FROM notification_delivery", String.class)).isEqualTo("SENT");
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void concurrentRegistrationReturnsSameRunAndLockedRunIsSkipped() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<UUID> first = pool.submit(() -> admin.createReportRun(MONTH, "v1").id());
			Future<UUID> second = pool.submit(() -> admin.createReportRun(MONTH, "v1").id());
			UUID id = first.get(5, TimeUnit.SECONDS);
			assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(id);
			tx.executeWithoutResult(status -> {
				runs.lock(id).orElseThrow();
				try {
					pool.submit(() -> worker.processReportRunStep(id)).get(5, TimeUnit.SECONDS);
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

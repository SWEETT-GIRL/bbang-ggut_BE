package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.MONTH;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.PAYLOAD;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.monthlyreport.config.MonthlyReportBatchProperties;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportJob;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMember;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMemberId;
import sweetgirl.croissant.monthlyreport.enums.MonthlyReportJobStatus;
import sweetgirl.croissant.monthlyreport.enums.MonthlyReportStatus;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportJobRepository;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportNotificationRepository;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportSourceHistoryRepository;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportBatchService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportGenerationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportIngestRecoveryService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportJobPublisher;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportJobService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportJobWorker;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportPayloadCodec;

@Tag("lab")
@EnabledIfEnvironmentVariable(named = "MONTHLY_REPORT_TEST_JDBC_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MonthlyReportJobTest {
	private final String schema = "monthly_jobs_" + UUID.randomUUID().toString().replace("-", "");
	private final ObjectMapper mapper = new ObjectMapper();
	private final ValidatorFactory validation = Validation.buildDefaultValidatorFactory();
	private final MonthlyReportPayloadCodec codec = new MonthlyReportPayloadCodec(mapper, validation.getValidator());
	private final MonthlyReportGenerationService generation = mock(MonthlyReportGenerationService.class);
	private final MonthlyReportIngestRecoveryService recovery = mock(MonthlyReportIngestRecoveryService.class);
	private final MonthlyReportSourceHistoryRepository history = mock(MonthlyReportSourceHistoryRepository.class);
	private final MonthlyReportBatchProperties properties = new MonthlyReportBatchProperties();
	private AnnotationConfigApplicationContext context;
	private JdbcTemplate jdbc;
	private MonthlyReportJobRepository jobs;
	private MonthlyReportRepository reports;
	private MonthlyReportJobService registration;
	private MonthlyReportJobWorker worker;
	private MonthlyReportBatchService batch;
	private TransactionTemplate transactions;

	@BeforeAll
	void setUp() {
		DriverManagerDataSource source = new DriverManagerDataSource(System.getenv("MONTHLY_REPORT_TEST_JDBC_URL"),
			System.getenv("MONTHLY_REPORT_TEST_DB_USER"), System.getenv("MONTHLY_REPORT_TEST_DB_PASSWORD"));
		jdbc = new JdbcTemplate(source);
		jdbc.execute("CREATE SCHEMA " + schema);
		source.setSchema(schema);
		jdbc.execute("""
				CREATE TABLE member (id bigint PRIMARY KEY, member_type varchar(20), created_at timestamp,
					deleted_at timestamp)
				""");
		new ResourceDatabasePopulator(
			new ClassPathResource("db/migration/V20261005222045__create_monthly_report_member.sql"),
			new ClassPathResource("db/migration/V20261005222046__add_monthly_report_appearance.sql"),
			new ClassPathResource("db/migration/V20261005222051__create_monthly_report_jobs.sql")).execute(source);
		LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
		factory.setDataSource(source);
		factory.setManagedTypes(PersistenceManagedTypes.of(MonthlyReportJob.class.getName(),
			MonthlyReportMember.class.getName(), Member.class.getName()));
		factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none", "hibernate.default_schema", schema,
			"hibernate.physical_naming_strategy",
			"org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"));
		factory.afterPropertiesSet();
		EntityManagerFactory emf = factory.getObject();
		context = new AnnotationConfigApplicationContext();
		context.registerBean("entityManagerFactory", EntityManagerFactory.class, () -> emf);
		context.registerBean("transactionManager", JpaTransactionManager.class, () -> new JpaTransactionManager(emf));
		context.registerBean(MemberRepository.class, () -> new JpaRepositoryFactory(
			SharedEntityManagerCreator.createSharedEntityManager(emf)).getRepository(MemberRepository.class));
		context.registerBean(MonthlyReportGenerationService.class, () -> generation);
		context.registerBean(MonthlyReportIngestRecoveryService.class, () -> recovery);
		context.registerBean(MonthlyReportSourceHistoryRepository.class, () -> history);
		context.registerBean(MonthlyReportBatchProperties.class, () -> properties);
		context.registerBean(MonthlyReportPayloadCodec.class, () -> codec);
		context.registerBean(ObjectMapper.class, () -> mapper);
		context.register(JpaConfig.class, MonthlyReportJobService.class, MonthlyReportJobWorker.class,
			MonthlyReportJobPublisher.class, MonthlyReportBatchService.class);
		context.refresh();
		jobs = context.getBean(MonthlyReportJobRepository.class);
		reports = context.getBean(MonthlyReportRepository.class);
		registration = context.getBean(MonthlyReportJobService.class);
		worker = context.getBean(MonthlyReportJobWorker.class);
		batch = context.getBean(MonthlyReportBatchService.class);
		transactions = new TransactionTemplate(context.getBean(JpaTransactionManager.class));
	}

	@BeforeEach
	void fixtures() {
		jdbc.execute("TRUNCATE monthly_report_job, monthly_report_member, member");
		jdbc.update("INSERT INTO member VALUES (123, 'USER', '2025-01-01', NULL), (456, 'USER', '2025-01-01', NULL)");
		reset(generation, recovery, history);
		properties.setMaxAttempts(5);
		properties.setPageSize(1);
		given(history.findCoveredFrom()).willReturn(LocalDateTime.of(2025, 12, 1, 0, 0));
		given(generation.publishFromDraft(any(), any(), any())).willAnswer(call -> publish(call.getArgument(0)));
	}

	@AfterAll
	void cleanUp() {
		if (context != null) {
			context.close();
		}
		if (jdbc != null) {
			jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
		}
		validation.close();
	}

	private boolean enqueue(long memberId) {
		MonthlyReportResponse draft = codec.read(PAYLOAD.replace("\"memberId\": 123", "\"memberId\": " + memberId),
			memberId, MONTH);
		return registration.enqueue(draft, mapper.createObjectNode().put("prepared", true), "policy-test-v1");
	}

	private boolean publish(MonthlyReportResponse draft) {
		return reports.publish(draft.memberId(), draft.yearMonth(),
			codec.write(draft, draft.memberId(), draft.yearMonth()),
			"{\"generated\":true}", "policy-test-v1", Instant.now().minusSeconds(1), Instant.now()) == 1;
	}

	@Test
	void enqueueIsIdempotentAndDoesNotGenerateOrRecoverSources() {
		assertThat(enqueue(123)).isTrue();
		assertThat(enqueue(123)).isFalse();
		assertThat(jobs.count()).isEqualTo(1);
		verifyNoInteractions(generation, recovery);
		jdbc.update("UPDATE member SET member_type = 'GUEST' WHERE id = 456");
		assertThat(enqueue(456)).isFalse();
	}

	@Test
	void failedPublicationRollsBackAndOtherMembersContinueThenOnlyFailureRetries() {
		enqueue(123);
		enqueue(456);
		AtomicBoolean firstFailure = new AtomicBoolean(true);
		willAnswer(call -> {
			MonthlyReportResponse draft = call.getArgument(0);
			boolean created = publish(draft);
			if (draft.memberId() == 123 && firstFailure.getAndSet(false)) {
				throw new IllegalStateException("test failure after report insert");
			}
			return created;
		}).given(generation).publishFromDraft(any(), any(), any());
		assertThat(batch.runDue()).isEqualTo(new MonthlyReportBatchService.Result(1, 1, 0, 0));
		MonthlyReportMemberId failedId = new MonthlyReportMemberId(123L, MONTH);
		assertThat(reports.findById(failedId).orElseThrow().getStatus()).isEqualTo(MonthlyReportStatus.FAILED);
		assertThat(reports.findById(failedId).orElseThrow().getPayload()).isNull();
		MonthlyReportJob failed = jobs.findById(failedId).orElseThrow();
		assertThat(failed.getStatus()).isEqualTo(MonthlyReportJobStatus.FAILED);
		assertThat(failed.getAttempts()).isEqualTo(1);
		assertThat(failed.getLastErrorCode()).isEqualTo("GENERATION_FAILED");
		assertThat(failed.getNextAttemptAt()).isAfter(Instant.now());
		assertThat(batch.runDue()).isEqualTo(new MonthlyReportBatchService.Result(0, 0, 0, 0));
		jdbc.update("UPDATE monthly_report_job SET next_attempt_at = now() WHERE member_id = 123");
		assertThat(batch.runDue()).isEqualTo(new MonthlyReportBatchService.Result(1, 0, 0, 0));
		assertThat(jobs.findById(failedId).orElseThrow().getAttempts()).isEqualTo(2);
		assertThat(jobs.findById(failedId).orElseThrow().getStatus()).isEqualTo(MonthlyReportJobStatus.COMPLETED);
		verify(generation, times(3)).publishFromDraft(any(), any(), any());
	}

	@Test
	void alreadyPublishedJobRecoversAfterProcessStopsBeforeJobCompletion() {
		enqueue(123);
		assertThat(worker.run(123L, MONTH)).isEqualTo(MonthlyReportJobWorker.Outcome.PUBLISHED);
		jdbc.update("UPDATE monthly_report_job SET status = 'READY' WHERE member_id = 123");
		clearInvocations(generation);
		assertThat(worker.run(123L, MONTH)).isEqualTo(MonthlyReportJobWorker.Outcome.SKIPPED);
		assertThat(jobs.findById(new MonthlyReportMemberId(123L, MONTH)).orElseThrow().getStatus())
			.isEqualTo(MonthlyReportJobStatus.COMPLETED);
		verifyNoInteractions(generation);
		transactions.executeWithoutResult(status -> assertThat(reports.recordGenerationFailure(123L, MONTH)).isZero());
		assertThat(reports.findById(new MonthlyReportMemberId(123L, MONTH)).orElseThrow().getStatus())
			.isEqualTo(MonthlyReportStatus.PUBLISHED);
	}

	@Test
	void concurrentWorkerSkipsLockedJobAndPublishesOnce() throws Exception {
		enqueue(123);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		willAnswer(call -> {
			entered.countDown();
			assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
			return publish(call.getArgument(0));
		}).given(generation).publishFromDraft(any(), any(), any());
		var executor = Executors.newSingleThreadExecutor();
		try {
			var running = executor.submit(() -> worker.run(123L, MONTH));
			assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
			assertThat(worker.run(123L, MONTH)).isEqualTo(MonthlyReportJobWorker.Outcome.SKIPPED);
			release.countDown();
			assertThat(running.get(10, TimeUnit.SECONDS)).isEqualTo(MonthlyReportJobWorker.Outcome.PUBLISHED);
			verify(generation).publishFromDraft(any(), any(), any());
		} finally {
			release.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	void exhaustedJobsStopAndIneligibleMembersAreCancelled() {
		enqueue(123);
		enqueue(456);
		properties.setMaxAttempts(1);
		willThrow(new IllegalStateException("failed")).given(generation).publishFromDraft(any(), any(), any());
		assertThat(worker.run(123L, MONTH)).isEqualTo(MonthlyReportJobWorker.Outcome.FAILED);
		jdbc.update("UPDATE monthly_report_job SET next_attempt_at = now() WHERE member_id = 123");
		assertThat(worker.run(123L, MONTH)).isEqualTo(MonthlyReportJobWorker.Outcome.SKIPPED);
		jdbc.update("UPDATE member SET deleted_at = now() WHERE id = 456");
		assertThat(worker.run(456L, MONTH)).isEqualTo(MonthlyReportJobWorker.Outcome.SKIPPED);
		assertThat(jobs.findById(new MonthlyReportMemberId(456L, MONTH)).orElseThrow().getStatus())
			.isEqualTo(MonthlyReportJobStatus.CANCELLED);
		verify(generation).publishFromDraft(any(), any(), any());
	}

	@Test
	void sharedIngestFailureDoesNotConsumeMemberAttempts() {
		enqueue(123);
		given(recovery.ensureComplete(MONTH)).willThrow(new IllegalStateException("shared ingest failed"));
		assertThat(batch.runDue()).isEqualTo(new MonthlyReportBatchService.Result(0, 0, 0, 1));
		assertThat(jobs.findById(new MonthlyReportMemberId(123L, MONTH)).orElseThrow().getAttempts()).isZero();
		verifyNoInteractions(generation);
	}

	@Test
	void unsupportedMonthDoesNotReingestSourcesOrConsumeMemberAttempts() {
		enqueue(123);
		given(history.findCoveredFrom()).willReturn(LocalDateTime.of(2026, 1, 1, 0, 0));
		assertThat(batch.runDue()).isEqualTo(new MonthlyReportBatchService.Result(0, 0, 0, 1));
		assertThat(jobs.findById(new MonthlyReportMemberId(123L, MONTH)).orElseThrow().getAttempts()).isZero();
		verifyNoInteractions(recovery, generation);
	}

	@TestConfiguration
	@EnableTransactionManagement
	@EnableJpaRepositories(basePackageClasses = MonthlyReportRepository.class,
			// 이 테스트는 발행·작업 저장소만 스캔한다.
			excludeFilters = @Filter(type = ASSIGNABLE_TYPE, classes = {MonthlyReportSourceHistoryRepository.class,
				MonthlyReportNotificationRepository.class}))
	static class JpaConfig {
		// 트랜잭션 프록시로 실제 DB 롤백과 회원별 커밋을 검증한다.
	}
}

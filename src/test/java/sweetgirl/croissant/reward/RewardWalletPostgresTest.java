package sweetgirl.croissant.reward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManagerFactory;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.reward.entity.CrumbLedgerEntry;
import sweetgirl.croissant.reward.entity.MemberCrumbWallet;
import sweetgirl.croissant.reward.entity.MemberPendingReward;
import sweetgirl.croissant.reward.entity.RewardSetting;
import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.enums.RewardRefType;
import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.exception.RewardException;
import sweetgirl.croissant.reward.repository.CrumbLedgerRepository;
import sweetgirl.croissant.reward.repository.MemberCrumbWalletRepository;
import sweetgirl.croissant.reward.repository.MemberPendingRewardRepository;
import sweetgirl.croissant.reward.repository.RewardSettingRepository;
import sweetgirl.croissant.reward.service.CrumbGrantService;
import sweetgirl.croissant.reward.service.GrantResult;

/**
 * 실제 PostgreSQL 에서 리워드 마이그레이션·엔티티 매핑·지갑 잠금을 검증한다.
 *
 * <p>기본 테스트는 엔티티로 만든 H2 스키마에서 돈다 — 마이그레이션 SQL 은 한 줄도 실행되지 않고, 행 잠금도 H2 의 것이다.
 * 여기서는 마이그레이션을 그대로 돌린 스키마에 엔티티를 대 보고({@code validate}), 같은 서비스를 진짜 잠금 위에서 돌린다.
 * 실행: 환경 변수 {@code REWARD_TEST_JDBC_URL=jdbc:postgresql://localhost:5432/soboro} 를 주고
 * {@code ./gradlew test -PlabTests --tests "*RewardWalletPostgresTest"}
 */
@Tag("lab")
@EnabledIfEnvironmentVariable(named = "REWARD_TEST_JDBC_URL", matches = "jdbc:postgresql://localhost:\\d+/.+")
class RewardWalletPostgresTest {

	private static final int THREADS = 8;
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);

	private final String schema = "reward_test_" + UUID.randomUUID().toString().replace("-", "");
	private final ExecutorService executor = Executors.newFixedThreadPool(THREADS);

	private JdbcTemplate jdbc;
	private EntityManagerFactory emf;
	private AnnotationConfigApplicationContext context;
	private CrumbGrantService crumbGrantService;

	@Configuration
	@EnableTransactionManagement
	static class TransactionConfig {
		// @EnableTransactionManagement 를 얹을 자리 — CrumbGrantService 의 @Transactional 이 실제로 걸리게 한다
	}

	@BeforeEach
	void setup() throws Exception {
		DriverManagerDataSource source = new DriverManagerDataSource(System.getenv("REWARD_TEST_JDBC_URL"),
			"sweetgirl", "soboro-lab");
		jdbc = new JdbcTemplate(source);
		jdbc.execute("CREATE SCHEMA " + schema);
		source.setSchema(schema);
		jdbc.execute("CREATE TABLE member (id bigint PRIMARY KEY, created_at timestamp NOT NULL)");
		jdbc.execute("CREATE TABLE oauth (id bigint PRIMARY KEY, member_id bigint NOT NULL, "
			+ "created_at timestamp NOT NULL)");
		// 1: 처음부터 소셜 가입 / 2: 게스트로 쓰다 승격 / 3: 탈퇴 후 재가입(옛 연결 행이 옮겨 왔다) / 4: 게스트
		jdbc.execute("""
				INSERT INTO member (id, created_at) VALUES
					(1, '2026-08-01 10:00:00'), (2, '2026-08-01 10:00:00'),
					(3, '2026-09-20 10:00:00'), (4, '2026-10-01 10:00:00')
				""");
		jdbc.execute("""
				INSERT INTO oauth (id, member_id, created_at) VALUES
					(1, 1, '2026-08-01 10:00:00'), (2, 2, '2026-09-05 10:00:00'), (3, 3, '2026-07-01 10:00:00')
				""");
		for (String migration : List.of(
			"V20261012200000__create_crumb_wallet_and_ledger.sql",
			"V20261012200100__create_reward_settings.sql",
			"V20261012200200__add_registration_columns_to_member.sql")) {
			jdbc.execute(new ClassPathResource("db/migration/" + migration).getContentAsString(StandardCharsets.UTF_8));
		}

		LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
		factory.setDataSource(source);
		factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		factory.setManagedTypes(PersistenceManagedTypes.of(
			MemberCrumbWallet.class.getName(), CrumbLedgerEntry.class.getName(),
			MemberPendingReward.class.getName(), RewardSetting.class.getName()));
		// 마이그레이션이 만든 표와 엔티티가 어긋나면 여기서 터진다 — 운영 기동 실패를 미리 본다
		// 이름 전략은 앱(Spring Boot 기본값)과 같게 둔다 — balanceAfter 를 balance_after 로 읽는다
		factory.setJpaPropertyMap(Map.of(
			"hibernate.hbm2ddl.auto", "validate",
			"hibernate.physical_naming_strategy",
			"org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
		factory.afterPropertiesSet();
		emf = factory.getObject();

		JpaRepositoryFactory repositories = new JpaRepositoryFactory(
			SharedEntityManagerCreator.createSharedEntityManager(emf));
		context = new AnnotationConfigApplicationContext();
		context.register(TransactionConfig.class);
		context.registerBean(JpaTransactionManager.class, () -> new JpaTransactionManager(emf));
		context.registerBean(MemberCrumbWalletRepository.class,
			() -> repositories.getRepository(MemberCrumbWalletRepository.class));
		context.registerBean(CrumbLedgerRepository.class,
			() -> repositories.getRepository(CrumbLedgerRepository.class));
		context.registerBean(MemberPendingRewardRepository.class,
			() -> repositories.getRepository(MemberPendingRewardRepository.class));
		context.registerBean(RewardSettingRepository.class,
			() -> repositories.getRepository(RewardSettingRepository.class));
		// 제재 표는 이 스키마에 없다 — 아무도 제재 중이 아닌 것으로 둔다
		context.registerBean(MemberRestrictionReader.class, () -> mock(MemberRestrictionReader.class));
		context.registerBean(CrumbGrantService.class);
		context.refresh();
		crumbGrantService = context.getBean(CrumbGrantService.class);
	}

	@AfterEach
	void cleanup() {
		executor.shutdownNow();
		if (context != null) {
			context.close();
		}
		if (emf != null) {
			emf.close();
		}
		if (jdbc != null) {
			// 이 테스트에서 생성한 UUID 스키마만 정리한다.
			jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
		}
	}

	@Test
	@DisplayName("가입 시각은 승격한 회원은 소셜 연결이 생긴 때, 재가입한 회원은 새 행이 생긴 때로 채워진다")
	void backfillsRegisteredAt() {
		assertThat(registeredAt(1)).isEqualTo(LocalDateTime.of(2026, 8, 1, 10, 0));
		assertThat(registeredAt(2)).isEqualTo(LocalDateTime.of(2026, 9, 5, 10, 0));
		assertThat(registeredAt(3)).isEqualTo(LocalDateTime.of(2026, 9, 20, 10, 0));
		assertThat(registeredAt(4)).isNull();
	}

	@Test
	@DisplayName("시드한 보상 수치의 키는 코드가 아는 키와 하나도 어긋나지 않는다")
	void seedsEveryKnownSetting() {
		List<RewardSetting> seeded = context.getBean(RewardSettingRepository.class).findAll();

		assertThat(seeded).hasSize(RewardSettingKey.values().length);
		for (RewardSetting setting : seeded) {
			assertThat(RewardSettingKey.findByKey(setting.getSettingKey()))
				.as(setting.getSettingKey())
				.hasValueSatisfying(key -> assertThat(setting.getIntValue()).isEqualTo(key.getDefaultValue()));
		}
	}

	@Test
	@DisplayName("같은 멱등 키로 동시에 적립해도 원장은 한 줄, 잔액은 한 번만 오른다")
	void sameKeyGrantsOnce() throws Exception {
		List<Callable<GrantResult>> tasks = new ArrayList<>();
		for (int index = 0; index < THREADS; index++) {
			tasks.add(() -> crumbGrantService.grant(1L, CrumbReason.RECORD, 10, "record:1", RewardRefType.RECORD, 1L));
		}

		List<GrantResult> results = runTogether(tasks);

		assertThat(results).filteredOn(GrantResult::isGranted).hasSize(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM crumb_ledger WHERE member_id = 1", Integer.class))
			.isEqualTo(1);
		assertThat(crumbGrantService.balanceOf(1L)).isEqualTo(10);
		assertThat(jdbc.queryForObject("SELECT balance_after FROM crumb_ledger WHERE member_id = 1", Integer.class))
			.isEqualTo(10);
	}

	@Test
	@DisplayName("상한이 3 인 적립이 서로 다른 키로 동시에 들어와도 3 번까지만 준다")
	void dailyCapHoldsUnderRace() throws Exception {
		List<Callable<GrantResult>> tasks = new ArrayList<>();
		for (int index = 0; index < THREADS; index++) {
			long recordId = index + 1L;
			tasks.add(() -> crumbGrantService.grantWithDailyCap(
				2L, CrumbReason.RECORD, 10, "record:" + recordId, RewardRefType.RECORD, recordId, 3, TODAY));
		}

		List<GrantResult> results = runTogether(tasks);

		assertThat(results).filteredOn(GrantResult::isGranted).hasSize(3);
		assertThat(results).filteredOn(GrantResult::isCapped).hasSize(THREADS - 3);
		assertThat(crumbGrantService.balanceOf(2L)).isEqualTo(30);
		// 지갑 잠금이 한 줄로 세웠다면 원장의 잔액은 10 · 20 · 30 으로 이어진다
		assertThat(jdbc.queryForList(
			"SELECT balance_after FROM crumb_ledger WHERE member_id = 2 ORDER BY id", Integer.class))
			.containsExactly(10, 20, 30);
	}

	@Test
	@DisplayName("한 번만 살 수 있는 잔액에서 동시에 쓰면 하나만 성공하고 잔액은 음수가 되지 않는다")
	void spendOnceWhenBalanceAllowsOne() throws Exception {
		crumbGrantService.grantByAdmin(3L, 600, "admin:seed", "테스트 준비");
		List<Callable<Boolean>> tasks = new ArrayList<>();
		for (int index = 0; index < THREADS; index++) {
			String key = "wake:friend" + index;
			tasks.add(() -> {
				try {
					return crumbGrantService.spend(3L, CrumbReason.WAKE_FRIEND, 600, key).spent();
				} catch (RewardException insufficient) {
					return false;
				}
			});
		}

		List<Boolean> results = runTogether(tasks);

		assertThat(results).filteredOn(Boolean::booleanValue).hasSize(1);
		assertThat(crumbGrantService.balanceOf(3L)).isZero();
	}

	@Test
	@DisplayName("연출할 보상의 JSON 은 jsonb 칸에 들어갔다 그대로 나온다")
	void pendingPayloadRoundTrips() {
		MemberPendingRewardRepository pendingRewards = context.getBean(MemberPendingRewardRepository.class);
		new TransactionTemplate(context.getBean(JpaTransactionManager.class)).executeWithoutResult(
			status -> pendingRewards.save(MemberPendingReward.of(1L, "level_up:3",
				"{\"type\":\"LEVEL_UP\",\"from\":2,\"to\":3}", LocalDateTime.now())));

		assertThat(pendingRewards.existsByMemberIdAndDedupeKey(1L, "level_up:3")).isTrue();
		assertThat(jdbc.queryForObject(
			"SELECT payload ->> 'type' FROM member_pending_rewards WHERE member_id = 1", String.class))
			.isEqualTo("LEVEL_UP");
	}

	private LocalDateTime registeredAt(long memberId) {
		return jdbc.queryForObject("SELECT registered_at FROM member WHERE id = ?", LocalDateTime.class, memberId);
	}

	/** 모든 스레드를 한 출발선에 세웠다가 동시에 푼다. */
	private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
		CountDownLatch startLine = new CountDownLatch(1);
		List<Future<T>> futures = new ArrayList<>();
		for (Callable<T> task : tasks) {
			futures.add(executor.submit(() -> {
				startLine.await();
				return task.call();
			}));
		}
		startLine.countDown();
		List<T> results = new ArrayList<>();
		for (Future<T> future : futures) {
			results.add(future.get(30, TimeUnit.SECONDS));
		}
		return results;
	}
}

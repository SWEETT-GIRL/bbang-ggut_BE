package sweetgirl.croissant.reward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import sweetgirl.croissant.reward.entity.BbangdaengLetter;
import sweetgirl.croissant.reward.entity.CrumbLedgerEntry;
import sweetgirl.croissant.reward.entity.MemberAttendanceDay;
import sweetgirl.croissant.reward.entity.MemberBbangdaengLetter;
import sweetgirl.croissant.reward.entity.MemberCharacterLook;
import sweetgirl.croissant.reward.entity.MemberCrumbWallet;
import sweetgirl.croissant.reward.entity.MemberFeedDay;
import sweetgirl.croissant.reward.entity.MemberInvite;
import sweetgirl.croissant.reward.entity.MemberPendingReward;
import sweetgirl.croissant.reward.entity.MemberShopItem;
import sweetgirl.croissant.reward.entity.RankingWeeklyReward;
import sweetgirl.croissant.reward.entity.RewardSetting;
import sweetgirl.croissant.reward.entity.ShopCatalog;
import sweetgirl.croissant.reward.entity.ShopItem;
import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.enums.RewardRefType;
import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.exception.RewardException;
import sweetgirl.croissant.reward.repository.CrumbLedgerRepository;
import sweetgirl.croissant.reward.repository.MemberAttendanceDayRepository;
import sweetgirl.croissant.reward.repository.MemberCrumbWalletRepository;
import sweetgirl.croissant.reward.repository.MemberFeedDayRepository;
import sweetgirl.croissant.reward.repository.MemberPendingRewardRepository;
import sweetgirl.croissant.reward.repository.RankingWeeklyRewardRepository;
import sweetgirl.croissant.reward.repository.RewardPushTargetRepository;
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
		jdbc.execute("CREATE TABLE member (id bigint PRIMARY KEY, created_at timestamp NOT NULL, "
			+ "deleted_at timestamp, member_type varchar(20) NOT NULL DEFAULT 'USER')");
		jdbc.execute("CREATE TABLE refresh_token (id bigserial PRIMARY KEY, member_id bigint NOT NULL, "
			+ "created_at timestamp NOT NULL)");
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
		// 기록 쪽 마이그레이션이 고치는 표들 — 지금 운영의 모양(사진 · 좌표 필수, 지구 하늘 3 · 우주 7)으로 둔다
		jdbc.execute("CREATE TABLE bread_record (id bigint PRIMARY KEY, member_id bigint NOT NULL)");
		jdbc.execute("CREATE TABLE bread_record_tag (id bigserial PRIMARY KEY, record_id bigint NOT NULL, "
			+ "photo_id bigint NOT NULL, x double precision NOT NULL, y double precision NOT NULL)");
		jdbc.execute("INSERT INTO bread_record (id, member_id) VALUES (10, 1)");
		jdbc.execute("INSERT INTO bread_record_tag (record_id, photo_id, x, y) VALUES (10, 100, 0.3, 0.4)");
		jdbc.execute("CREATE TABLE world_planet_stages (planet_code varchar(30), code varchar(20), offset_at int)");
		jdbc.execute("""
				INSERT INTO world_planet_stages (planet_code, code, offset_at) VALUES
					('earth', 'village', 0), ('earth', 'sky', 3), ('earth', 'space', 7), ('mars', 'sky', 10)
				""");
		jdbc.execute("CREATE TABLE world_catalog (id smallint PRIMARY KEY, version int NOT NULL, "
			+ "updated_at timestamp NOT NULL)");
		jdbc.execute("INSERT INTO world_catalog (id, version, updated_at) VALUES (1, 7, '2026-09-20 10:00:00')");
		// 상점 마이그레이션이 가리키거나 고치는 세계관 표들
		jdbc.execute("CREATE TABLE world_characters (code varchar(30) PRIMARY KEY)");
		jdbc.execute("INSERT INTO world_characters (code) VALUES ('dog')");
		jdbc.execute("CREATE TABLE member_world (member_id bigint PRIMARY KEY, updated_at timestamp)");
		jdbc.execute("CREATE TABLE member_characters (member_id bigint, character_code varchar(30), "
			+ "PRIMARY KEY (member_id, character_code))");
		// 빵투어 보상이 고치거나 읽는 표들
		jdbc.execute("CREATE TABLE course_progress (member_id bigint, course_id bigint, "
			+ "PRIMARY KEY (member_id, course_id))");
		jdbc.execute("CREATE TABLE course_stores (course_id bigint, store_id bigint, deleted_at timestamp)");
		jdbc.execute("CREATE TABLE course_store_visits (course_id bigint, member_id bigint, store_id bigint, "
			+ "bread_record_id bigint, deleted_at timestamp)");
		jdbc.execute("CREATE TABLE bread_record_photo (id bigserial PRIMARY KEY, record_id bigint NOT NULL)");
		for (String migration : List.of(
			"V20261012200000__create_crumb_wallet_and_ledger.sql",
			"V20261012200100__create_reward_settings.sql",
			"V20261012200200__add_registration_columns_to_member.sql",
			"V20261012210000__create_reward_daily_tables.sql",
			"V20261012220000__allow_photoless_bread_record.sql",
			"V20261012220100__validate_bread_record_tag_pinned.sql",
			"V20261012220200__advance_earth_stage_offsets.sql",
			"V20261012230000__create_bbangdaeng_letters.sql",
			"V20261013000000__create_reward_shop.sql",
			"V20261013010000__add_reward_columns_to_course_progress.sql",
			"V20261013020000__create_member_invites.sql",
			"V20261013030000__create_ranking_weekly_rewards.sql",
			"V20261016002000__add_ranking_weekly_rewards_score.sql",
			"V20261016003000__add_shop_default_poses.sql")) {
			jdbc.execute(new ClassPathResource("db/migration/" + migration).getContentAsString(StandardCharsets.UTF_8));
		}

		LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
		factory.setDataSource(source);
		factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		factory.setManagedTypes(PersistenceManagedTypes.of(
			MemberCrumbWallet.class.getName(), CrumbLedgerEntry.class.getName(),
			MemberPendingReward.class.getName(), RewardSetting.class.getName(),
			MemberAttendanceDay.class.getName(), MemberFeedDay.class.getName(),
			BbangdaengLetter.class.getName(), MemberBbangdaengLetter.class.getName(),
			ShopItem.class.getName(), ShopCatalog.class.getName(), MemberShopItem.class.getName(),
			MemberCharacterLook.class.getName(), MemberInvite.class.getName(),
			RankingWeeklyReward.class.getName()));
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
		context.registerBean(MemberAttendanceDayRepository.class,
			() -> repositories.getRepository(MemberAttendanceDayRepository.class));
		context.registerBean(MemberFeedDayRepository.class,
			() -> repositories.getRepository(MemberFeedDayRepository.class));
		context.registerBean(RankingWeeklyRewardRepository.class,
			() -> repositories.getRepository(RankingWeeklyRewardRepository.class));
		context.registerBean(RewardPushTargetRepository.class,
			() -> repositories.getRepository(RewardPushTargetRepository.class));
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

	@Test
	@DisplayName("같은 날의 출석은 PK 가 한 줄만 받고, 날짜로 그날 출석한 회원을 찾는다")
	void attendanceDayIsOnePerMemberPerDay() {
		MemberAttendanceDayRepository attendanceDays = context.getBean(MemberAttendanceDayRepository.class);
		TransactionTemplate transaction = new TransactionTemplate(context.getBean(JpaTransactionManager.class));

		int first = transaction.execute(status -> attendanceDays.insertIfAbsent(1L, TODAY, 3, 10, LocalDateTime.now()));
		int second = transaction.execute(status -> attendanceDays.insertIfAbsent(1L, TODAY, 4, 5, LocalDateTime.now()));
		transaction.executeWithoutResult(status -> attendanceDays.updateCrumbs(1L, TODAY, 0));

		assertThat(first).isEqualTo(1);
		assertThat(second).isZero();
		assertThat(attendanceDays.findByMemberIdAndDay(1L, TODAY)).hasValueSatisfying(day -> {
			assertThat(day.getBoardDay()).isEqualTo(3);
			assertThat(day.getCrumbs()).isZero();
		});
		assertThat(jdbc.queryForList(
			"SELECT member_id FROM member_attendance_days WHERE attended_on = ?", Long.class, TODAY))
			.containsExactly(1L);
	}

	@Test
	@DisplayName("8 일째 칸은 DB 가 받지 않는다")
	void boardDayIsBoundedByCheck() {
		MemberAttendanceDayRepository attendanceDays = context.getBean(MemberAttendanceDayRepository.class);
		TransactionTemplate transaction = new TransactionTemplate(context.getBean(JpaTransactionManager.class));

		assertThatThrownBy(() -> transaction.execute(
			status -> attendanceDays.insertIfAbsent(1L, TODAY, 8, 5, LocalDateTime.now())))
			.hasMessageContaining("chk_member_attendance_board_day");
	}

	@Test
	@DisplayName("밥 룰렛 결과도 하루에 한 줄이다")
	void feedDayIsOnePerMemberPerDay() {
		MemberFeedDayRepository feedDays = context.getBean(MemberFeedDayRepository.class);
		TransactionTemplate transaction = new TransactionTemplate(context.getBean(JpaTransactionManager.class));

		int first = transaction.execute(status -> feedDays.insertIfAbsent(1L, TODAY, 5, 50, 3, LocalDateTime.now()));
		int second = transaction.execute(status -> feedDays.insertIfAbsent(1L, TODAY, 0, 10, 3, LocalDateTime.now()));

		assertThat(first).isEqualTo(1);
		assertThat(second).isZero();
		assertThat(feedDays.findByMemberIdAndDay(1L, TODAY)).hasValueSatisfying(
			day -> assertThat(day.getWedge()).isEqualTo(5));
	}

	@Test
	@DisplayName("사진에 안 붙은 빵 이름은 사진 · 좌표를 모두 비워야 들어가고, 하나만 비우면 DB 가 받지 않는다")
	void tagIsPinnedOrNotAtAll() {
		jdbc.execute("INSERT INTO bread_record_tag (record_id, photo_id, x, y) VALUES (10, NULL, NULL, NULL)");

		assertThatThrownBy(() -> jdbc.execute(
			"INSERT INTO bread_record_tag (record_id, photo_id, x, y) VALUES (10, 100, 0.3, NULL)"))
			.hasMessageContaining("chk_bread_record_tag_pinned");
		assertThatThrownBy(() -> jdbc.execute(
			"INSERT INTO bread_record_tag (record_id, photo_id, x, y) VALUES (10, NULL, 0.3, 0.4)"))
			.hasMessageContaining("chk_bread_record_tag_pinned");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bread_record_tag", Integer.class)).isEqualTo(2);
	}

	@Test
	@DisplayName("같은 회원의 같은 요청 번호는 기록 하나만 — 번호가 없는 기록과 다른 회원은 막지 않는다")
	void requestIdIsUniquePerMember() {
		String requestId = "7f1c9a52-0d43-4a0b-9d6e-0f2b6c1a8e11";
		jdbc.update("INSERT INTO bread_record (id, member_id, client_request_id) VALUES (11, 1, ?::uuid)", requestId);
		jdbc.update("INSERT INTO bread_record (id, member_id, client_request_id) VALUES (12, 2, ?::uuid)", requestId);
		jdbc.execute("INSERT INTO bread_record (id, member_id) VALUES (13, 1), (14, 1)");

		assertThatThrownBy(() -> jdbc.update(
			"INSERT INTO bread_record (id, member_id, client_request_id) VALUES (15, 1, ?::uuid)", requestId))
			.hasMessageContaining("uq_bread_record_client_request");
	}

	@Test
	@DisplayName("지구의 하늘 · 우주만 1 · 5 로 당겨지고 카탈로그 버전이 오른다")
	void advancesEarthStages() {
		assertThat(jdbc.queryForList(
			"SELECT offset_at FROM world_planet_stages WHERE planet_code = 'earth' ORDER BY offset_at", Integer.class))
			.containsExactly(0, 1, 5);
		assertThat(jdbc.queryForObject(
			"SELECT offset_at FROM world_planet_stages WHERE planet_code = 'mars'", Integer.class)).isEqualTo(10);
		assertThat(jdbc.queryForObject("SELECT version FROM world_catalog", Integer.class)).isEqualTo(8);
	}

	@Test
	@DisplayName("빵댕이 편지 12 통이 순서대로 시드되고, 줄바꿈이 글자가 아니라 줄바꿈으로 들어간다")
	void seedsTwelveLetters() {
		assertThat(jdbc.queryForList("SELECT seq FROM bbangdaeng_letters ORDER BY seq", Integer.class))
			.containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
		String first = jdbc.queryForObject("SELECT body FROM bbangdaeng_letters WHERE seq = 1", String.class);
		assertThat(first).startsWith("안녕! 나 빵댕이야.\n월요일마다").doesNotContain("\\n");
		assertThat(first.lines()).hasSize(4);
	}

	@Test
	@DisplayName("한 회원은 한 주에 편지 한 통만 받는다")
	void oneLetterPerMemberPerWeek() {
		Long letterId = jdbc.queryForObject("SELECT id FROM bbangdaeng_letters WHERE seq = 1", Long.class);
		String insert = "INSERT INTO member_bbangdaeng_letters "
			+ "(member_id, letter_id, week_start, visible_from, created_at) VALUES (?, ?, ?, ?, now())";
		jdbc.update(insert, 1L, letterId, TODAY, TODAY);
		jdbc.update(insert, 2L, letterId, TODAY, TODAY);

		assertThatThrownBy(() -> jdbc.update(insert, 1L, letterId, TODAY, TODAY.plusDays(1)))
			.hasMessageContaining("uq_member_bbangdaeng_letters_week");
	}

	@Test
	@DisplayName("상점 품목 10 개와 버전 1 이 시드되고, 소품은 계정에 하나 · 모습은 친구마다 하나만 살 수 있다")
	void shopSeedAndOwnership() {
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shop_items", Integer.class)).isEqualTo(10);
		assertThat(jdbc.queryForObject(
			"SELECT price FROM shop_items WHERE code = 'pose-bread-tower' AND lock_level = 5", Integer.class))
			.isEqualTo(180);
		assertThat(jdbc.queryForObject("SELECT version FROM shop_catalog", Integer.class)).isEqualTo(1);

		String buy = "INSERT INTO member_shop_items (member_id, character_code, item_code, purchased_at) "
			+ "VALUES (?, ?, ?, now()) ON CONFLICT DO NOTHING";
		assertThat(jdbc.update(buy, 1L, "", "prop-flag")).isEqualTo(1);
		assertThat(jdbc.update(buy, 1L, "", "prop-flag")).isZero();
		assertThat(jdbc.update(buy, 1L, "dog", "pose-joy")).isEqualTo(1);
		assertThat(jdbc.update(buy, 1L, "cat", "pose-joy")).isEqualTo(1);
		assertThat(jdbc.update(buy, 1L, "dog", "pose-joy")).isZero();
	}

	@Test
	@DisplayName("모습 품목은 포즈가 있어야 하고, 가격은 0 보다 커야 한다")
	void shopItemChecks() {
		assertThatThrownBy(() -> jdbc.execute(
			"INSERT INTO shop_items (code, kind, name, price, sort_order) VALUES ('pose-x', 'POSE', 'x', 10, 1)"))
			.hasMessageContaining("chk_shop_items_pose");
		assertThatThrownBy(() -> jdbc.execute(
			"INSERT INTO shop_items (code, kind, name, price, sort_order) VALUES ('prop-x', 'PROP', 'x', 0, 1)"))
			.hasMessageContaining("chk_shop_items_price");
	}

	@Test
	@DisplayName("사진 완주 판정 — 모든 가게에 사진 붙은 기록이 달려야 0 이고, 가게 없는 빵투어는 완주가 아니다")
	void countsStoresWithoutPhotoVisit() {
		jdbc.execute("INSERT INTO course_stores (course_id, store_id) VALUES (812, 10), (812, 11), (812, 12)");
		jdbc.execute("UPDATE course_stores SET deleted_at = now() WHERE store_id = 12");
		jdbc.execute("INSERT INTO bread_record (id, member_id) VALUES (21, 1), (22, 1)");
		jdbc.execute("INSERT INTO course_store_visits (course_id, member_id, store_id, bread_record_id) "
			+ "VALUES (812, 1, 10, 21), (812, 1, 11, 22)");
		jdbc.execute("INSERT INTO bread_record_photo (record_id) VALUES (21)");
		String query = """
				SELECT CASE WHEN COUNT(*) = 0 THEN 1 ELSE COUNT(*) FILTER (WHERE NOT EXISTS (
					SELECT 1 FROM course_store_visits visit
					JOIN bread_record_photo photo ON photo.record_id = visit.bread_record_id
					WHERE visit.course_id = course_store.course_id
						AND visit.store_id = course_store.store_id
						AND visit.member_id = ?
						AND visit.deleted_at IS NULL)) END
				FROM course_stores course_store
				WHERE course_store.course_id = ? AND course_store.deleted_at IS NULL
				""";

		// 가게 11 의 기록에 사진이 없다(지운 가게 12 는 세지 않는다)
		assertThat(jdbc.queryForObject(query, Long.class, 1L, 812L)).isEqualTo(1L);
		jdbc.execute("INSERT INTO bread_record_photo (record_id) VALUES (22)");
		assertThat(jdbc.queryForObject(query, Long.class, 1L, 812L)).isZero();
		// 다른 회원은 다녀오지 않았다
		assertThat(jdbc.queryForObject(query, Long.class, 2L, 812L)).isEqualTo(2L);
		// 가게가 하나도 없는 빵투어
		assertThat(jdbc.queryForObject(query, Long.class, 1L, 999L)).isEqualTo(1L);
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = current_schema() "
				+ "AND table_name = 'course_progress' AND column_name IN ('first_visited_on', 'photo_completed_at')",
			Integer.class)).isEqualTo(2);
	}

	@Test
	@DisplayName("초대 코드는 회원끼리 겹칠 수 없고, 코드가 없는 회원은 몇 명이든 된다")
	void inviteCodeIsUnique() {
		jdbc.execute("UPDATE member SET invite_code = 'ABCD2345' WHERE id = 1");

		assertThatThrownBy(() -> jdbc.execute("UPDATE member SET invite_code = 'ABCD2345' WHERE id = 2"))
			.hasMessageContaining("uq_member_invite_code");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member WHERE invite_code IS NULL", Integer.class))
			.isEqualTo(3);
	}

	@Test
	@DisplayName("한 사람은 코드를 한 번만 넣을 수 있고, 자기 자신을 초대할 수 없다")
	void inviteeEntersOnce() {
		String accept = "INSERT INTO member_invites (inviter_id, invitee_id, created_at) VALUES (?, ?, now()) "
			+ "ON CONFLICT DO NOTHING";
		assertThat(jdbc.update(accept, 1L, 2L)).isEqualTo(1);
		assertThat(jdbc.update(accept, 3L, 2L)).isZero();
		assertThat(jdbc.update(accept, 1L, 3L)).isEqualTo(1);

		assertThatThrownBy(() -> jdbc.update(accept, 4L, 4L))
			.hasMessageContaining("chk_member_invites_not_self");
	}

	@Test
	@DisplayName("랭킹 주간 보상 — 한 주에 한 사람 한 줄, 받기는 한 번, 기한이 지난 주는 못 받고, 왕관은 받는 주에만 쓴다")
	void rankingWeeklyRewards() {
		RankingWeeklyRewardRepository rewards = context.getBean(RankingWeeklyRewardRepository.class);
		TransactionTemplate transaction = new TransactionTemplate(context.getBean(JpaTransactionManager.class));
		LocalDate lastWeek = LocalDate.of(2026, 10, 5);
		LocalDate thisMonday = LocalDate.of(2026, 10, 12);
		LocalDate crownUntil = lastWeek.plusDays(13);
		LocalDateTime now = LocalDateTime.now();

		Integer first = transaction
			.execute(status -> rewards.insertIfAbsent(lastWeek, 1L, 1, 100, crownUntil, 12, now));
		Integer again = transaction
			.execute(status -> rewards.insertIfAbsent(lastWeek, 1L, 1, 100, crownUntil, 12, now));
		assertThat(first).isEqualTo(1);
		assertThat(again).isZero();
		transaction.execute(status -> rewards.insertIfAbsent(lastWeek, 2L, 2, 50, null, 7, now));
		transaction.execute(status -> rewards.insertIfAbsent(lastWeek.minusWeeks(6), 2L, 5, 20, null, null, now));

		// 왕관: 겨룬 주에는 아직 없고, 받는 주(10/12 ~ 10/18)에만 쓴다
		assertThat(rewards.findCrownedMemberIds(thisMonday)).containsExactly(1L);
		assertThat(rewards.findCrownedMemberIds(thisMonday.plusDays(6))).containsExactly(1L);
		assertThat(rewards.findCrownedMemberIds(thisMonday.plusDays(7))).isEmpty();

		// 6 주 전의 보상은 기한(겨룬 주의 월요일에서 34 일째까지)이 지나 목록에도 없고 받을 수도 없다
		LocalDate earliestWeek = thisMonday.minusDays(34);
		List<RankingWeeklyReward> claimable = rewards.findClaimable(2L, earliestWeek);
		assertThat(claimable).extracting(RankingWeeklyReward::getFinalRank).containsExactly(2);
		// 그 주 점수도 줄에 남는다 — 앱이 "지난주 빵 N개"를 보여 준다
		assertThat(claimable.get(0).getScore()).isEqualTo(7);
		Long rewardId = claimable.get(0).getId();
		Integer claimed = transaction.execute(status -> rewards.claim(rewardId, 2L, earliestWeek, now));
		Integer claimedAgain = transaction.execute(status -> rewards.claim(rewardId, 2L, earliestWeek, now));
		assertThat(claimed).isEqualTo(1);
		assertThat(claimedAgain).isZero();
		assertThat(rewards.findClaimable(2L, earliestWeek)).isEmpty();

		assertThatThrownBy(() -> transaction.execute(
			status -> rewards.insertIfAbsent(lastWeek, 3L, 11, 20, null, null, now)))
			.hasMessageContaining("chk_ranking_weekly_rewards_rank");
	}

	@Test
	@DisplayName("편지 알림 대상 — 최근에 토큰을 받은 회원만, 게스트와 탈퇴한 회원은 빠진다")
	void membersActiveSince() {
		jdbc.execute("UPDATE member SET member_type = 'GUEST' WHERE id = 4");
		jdbc.execute("UPDATE member SET deleted_at = now() WHERE id = 3");
		jdbc.execute("""
				INSERT INTO refresh_token (member_id, created_at) VALUES
					(1, '2026-10-10 09:00:00'), (1, '2026-10-11 09:00:00'), (2, '2026-08-01 09:00:00'),
					(3, '2026-10-10 09:00:00'), (4, '2026-10-10 09:00:00')
				""");
		RewardPushTargetRepository targets = context.getBean(RewardPushTargetRepository.class);

		assertThat(targets.findMemberIdsActiveSince(LocalDateTime.of(2026, 9, 12, 10, 0))).containsExactly(1L);
	}

	@Test
	@DisplayName("출석 리마인드 대상 — 어제 출석하고 오늘 안 한 회원과 어제 찍은 칸")
	void attendanceReminderTargets() {
		LocalDate yesterday = TODAY.minusDays(1);
		jdbc.update("INSERT INTO member_attendance_days (attended_on, member_id, board_day, crumbs, created_at) "
			+ "VALUES (?, 1, 2, 5, now()), (?, 2, 7, 30, now()), (?, 2, 1, 5, now()), (?, 3, 4, 5, now())",
			yesterday, yesterday, TODAY, yesterday.minusDays(1));
		RewardPushTargetRepository targets = context.getBean(RewardPushTargetRepository.class);

		// 2 번은 오늘 이미 했고, 3 번은 어제 안 해서 이미 끊겼다
		assertThat(targets.findAttendanceReminderTargets(yesterday, TODAY))
			.singleElement()
			.satisfies(target -> {
				assertThat(target.getMemberId()).isEqualTo(1L);
				assertThat(target.getBoardDay()).isEqualTo(2);
			});
	}

	@Test
	@DisplayName("같은 발송을 다른 연결이 돌리고 있으면 잠금을 못 얻는다")
	void advisoryLockExcludesAnotherRun() {
		RewardPushTargetRepository targets = context.getBean(RewardPushTargetRepository.class);
		TransactionTemplate first = new TransactionTemplate(context.getBean(JpaTransactionManager.class));

		Boolean secondGotLock = first.execute(status -> {
			assertThat(targets.tryAdvisoryXactLock(99_001L)).isTrue();
			try {
				return executor.submit(() -> new TransactionTemplate(context.getBean(JpaTransactionManager.class))
					.execute(other -> targets.tryAdvisoryXactLock(99_001L))).get(30, TimeUnit.SECONDS);
			} catch (Exception exception) {
				throw new IllegalStateException(exception);
			}
		});

		assertThat(secondGotLock).isFalse();
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

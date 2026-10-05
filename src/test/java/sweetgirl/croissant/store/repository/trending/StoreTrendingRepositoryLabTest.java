package sweetgirl.croissant.store.repository.trending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 평소 수준 SQL 이 PostgreSQL 에서 맞게 도는지 — COUNT(DISTINCT (회원, 날짜))·VALUES·ARRAY_AGG 는 H2 로 검증할 수 없다.
 * 모든 것을 한 트랜잭션 안에서 하고 되돌린다(랩 DB 에 흔적을 남기지 않는다).
 *
 * <p>실행: 랩 PG 를 띄운 뒤 {@code ./gradlew test -PlabTests}
 */
@Tag("lab")
class StoreTrendingRepositoryLabTest {

	/** 랩 DB(운영 덤프)의 '성심당 본점' */
	private static final long STORE_ID = 75L;
	private static final LocalDateTime TO = LocalDateTime.of(2026, 9, 28, 0, 0);
	private static final LocalDateTime FROM = TO.minusDays(28);

	private static NamedParameterJdbcTemplate jdbcTemplate;
	private static TransactionTemplate transactionTemplate;
	private static StoreTrendingRepository repository;

	@BeforeAll
	static void connect() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
			System.getProperty("lab.db.url", "jdbc:postgresql://localhost:5432/soboro"),
			System.getProperty("lab.db.user", "sweetgirl"),
			System.getProperty("lab.db.password", "soboro-lab"));
		jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
		transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		repository = new StoreTrendingRepository(jdbcTemplate);
	}

	private void inRollback(Runnable body) {
		transactionTemplate.executeWithoutResult(status -> {
			// 랩 덤프가 이 마이그레이션보다 옛것일 수 있다 — DDL 도 트랜잭션 안이라 함께 되돌려진다
			jdbcTemplate.getJdbcTemplate().execute(
				"ALTER TABLE store ADD COLUMN IF NOT EXISTS trending_baseline DOUBLE PRECISION NOT NULL DEFAULT 0");
			jdbcTemplate.getJdbcTemplate()
				.update("UPDATE store SET trending_baseline = 0 WHERE trending_baseline <> 0");
			body.run();
			status.setRollbackOnly();
		});
	}

	private void interaction(long memberId, String type, LocalDateTime occurredAt) {
		jdbcTemplate.update("""
				INSERT INTO user_store_interaction (member_id, store_id, type, occurred_at)
				VALUES (:member, :store, :type, :at)
				""", new MapSqlParameterSource()
			.addValue("member", memberId).addValue("store", STORE_ID).addValue("type", type)
			.addValue("at", occurredAt));
	}

	@Test
	@DisplayName("회원×종류×일 단위로 세어 가중합 ÷ 일수 — 같은 날 같은 회원의 반복과 기간 밖 신호는 빠진다")
	void refreshBaselines_countsMemberDaysWithWeights() {
		inRollback(() -> {
			LocalDateTime day1 = FROM.plusDays(1).plusHours(10);
			interaction(900_001L, "STORE_VIEW", day1);
			interaction(900_001L, "STORE_VIEW", day1.plusHours(3)); // 같은 날 또 봄 — 한 번
			interaction(900_002L, "STORE_VIEW", day1);
			interaction(900_001L, "STORE_VIEW", day1.plusDays(1)); // 다른 날 — 따로 센다
			interaction(900_003L, "SAVE", day1);
			interaction(900_004L, "RECORD_STORE", day1);
			interaction(900_005L, "STORE_VIEW", TO.plusHours(1)); // 최근 창 — 평소에 넣지 않는다
			interaction(900_006L, "UNSAVE", day1); // 가중치 없는 종류

			Map<String, Double> weights = new LinkedHashMap<>();
			weights.put("STORE_VIEW", 1.0);
			weights.put("SAVE", 2.0);
			weights.put("RECORD_STORE", 3.0);
			int updated = repository.refreshBaselines(weights, FROM, TO, 28);

			// 조회 3(회원·일) × 1 + 찜 1 × 2 + 빵로그 1 × 3 = 8 → 하루 8/28
			assertThat(updated).isGreaterThanOrEqualTo(1);
			assertThat(repository.findBaselines(Set.of(STORE_ID)).get(STORE_ID)).isCloseTo(8.0 / 28, within(1e-9));
		});
	}

	@Test
	@DisplayName("다시 돌려도 값이 같으면 갱신하지 않는다")
	void refreshBaselines_isIdempotent() {
		inRollback(() -> {
			interaction(900_001L, "SAVE", FROM.plusDays(2));
			Map<String, Double> weights = Map.of("SAVE", 2.0);
			repository.refreshBaselines(weights, FROM, TO, 28);

			assertThat(repository.refreshBaselines(weights, FROM, TO, 28)).isZero();
		});
	}

	@Test
	@DisplayName("이름 조회는 노출 중인 가게의 정규화 이름과 별칭을 함께 준다")
	void findActiveNames_includesNormalizedNameAndAliases() {
		List<TrendingStoreName> names = repository.findActiveNames(Set.of(STORE_ID));

		assertThat(names).singleElement().satisfies(name -> {
			assertThat(name.name()).isEqualTo("성심당 본점");
			assertThat(name.normalizedKeys()).first().isEqualTo("성심당본점");
		});
	}
}

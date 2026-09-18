package sweetgirl.croissant.store.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import sweetgirl.croissant.config.persistence.JpaConfig;

/**
 * 랩 PG(운영 덤프)에서 인기도 갱신 SQL 을 실제로 돌린다. 신호가 하나도 없는 가게 다섯을 골라 각각 다른 신호를 넣고, 회원 조회를 사람
 * 단위로 세는지 · 찜과 빵투어 담기가 한 번씩 더해지는지 · 익명 조회만 있는 가게가 0 인지 · 랭킹이 배율을 타는지 본다. p0 와 배율은 랩
 * 전체 데이터에 좌우되므로 SQL 과 같은 정의로 다시 계산해 기대값을 만든다.
 */
@Tag("lab")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@TestPropertySource(properties = {
	"spring.datasource.url=${lab.db.url:jdbc:postgresql://localhost:5432/soboro}",
	"spring.datasource.username=${lab.db.user:sweetgirl}",
	"spring.datasource.password=${lab.db.password:soboro-lab}",
	"spring.flyway.enabled=true",
	"spring.flyway.out-of-order=true",
	"spring.flyway.baseline-on-migrate=true",
	"spring.flyway.baseline-version=20260604.0",
	"spring.jpa.hibernate.ddl-auto=validate",
	"spring.jpa.show-sql=false"
})
class StorePopularityRepositoryLabTest {

	private static final int SMOOTHING = 10;
	private static final double QUANTILE = 0.95;

	@Autowired
	private StorePopularityRepository storePopularityRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Long converted;
	private Long anonymousOnly;
	private Long courseAddOnly;
	private Long rankedOnly;
	private Long untouched;
	private List<Long> members;

	@BeforeEach
	void pickFixtures() {
		List<Long> stores = jdbcTemplate.queryForList("""
				SELECT s.id FROM store s
				WHERE s.status = 'ACTIVE'
					AND NOT EXISTS (SELECT 1 FROM nationwide_hot_store_ranking x WHERE x.store_id = s.id)
					AND NOT EXISTS (SELECT 1 FROM request_log x WHERE x.store_id = s.id)
					AND NOT EXISTS (SELECT 1 FROM saved_stores x WHERE x.store_id = s.id)
					AND NOT EXISTS (SELECT 1 FROM course_stores x WHERE x.store_id = s.id)
				ORDER BY s.id
				LIMIT 5
				""", Long.class);
		assertThat(stores).hasSize(5);
		converted = stores.get(0);
		anonymousOnly = stores.get(1);
		courseAddOnly = stores.get(2);
		rankedOnly = stores.get(3);
		untouched = stores.get(4);
		members = jdbcTemplate.queryForList(
			"SELECT id FROM member WHERE deleted_at IS NULL ORDER BY id LIMIT 2", Long.class);
		assertThat(members).hasSize(2);
	}

	private void view(Long storeId, Long memberId) {
		jdbcTemplate.update("""
				INSERT INTO request_log
					(member_id, http_method, endpoint, response_status, store_id, event_type, created_at)
				VALUES (?, 'GET', '/stores/' || ?, 200, ?, 'STORE_VIEW', NOW())
				""", memberId, storeId, storeId);
	}

	private double score(Long storeId) {
		Double value = jdbcTemplate.queryForObject("SELECT popularity_score FROM store WHERE id = ?", Double.class,
			storeId);
		return value != null ? value : 0d;
	}

	/** SQL 과 같은 정의의 p0 = 전체 (찜 + 담기) ÷ 전체 조회 회원 */
	private double conversionBase() {
		return jdbcTemplate.queryForObject("""
				SELECT ((SELECT count(*) FROM saved_stores)
						+ (SELECT count(*) FROM course_stores cs JOIN courses c ON c.id = cs.course_id
							WHERE c.member_id IS NOT NULL AND cs.deleted_at IS NULL))::float8
					/ (SELECT sum(members) FROM (SELECT count(DISTINCT member_id) AS members FROM request_log
							WHERE event_type = 'STORE_VIEW' AND store_id IS NOT NULL AND member_id IS NOT NULL
							GROUP BY store_id) v)
				""", Double.class);
	}

	@Test
	@DisplayName("행동 점수 = 회원 조회 × min(1, 상대 전환율) + 찜 + 담기 — 재조회는 안 세고, 익명만 있으면 0, 없으면 0")
	void refresh_behaviorScore() {
		view(converted, members.get(0));
		view(converted, members.get(1));
		view(converted, members.get(0)); // 같은 회원의 두 번째 조회는 distinct 라 안 센다
		jdbcTemplate.update("INSERT INTO saved_stores (member_id, store_id, created_at) VALUES (?, ?, NOW())",
			members.get(0), converted);
		jdbcTemplate.update("INSERT INTO saved_stores (member_id, store_id, created_at) VALUES (?, ?, NOW())",
			members.get(1), converted);
		for (int i = 0; i < 5; i++) {
			view(anonymousOnly, null);
		}
		Long courseId = jdbcTemplate.queryForObject("""
				INSERT INTO courses (type, title, is_public, created_at, member_id)
				VALUES ('CUSTOM', '인기도 랩 픽스처', false, NOW(), ?)
				RETURNING id
				""", Long.class, members.get(0));
		jdbcTemplate.update(
			"INSERT INTO course_stores (course_id, store_id, visit_order, created_at) VALUES (?, ?, 1, NOW())",
			courseId, courseAddOnly);

		int updated = storePopularityRepository.refreshAllPopularityScores(SMOOTHING, QUANTILE);

		double p0 = conversionBase();
		double conversionRatio = Math.min(1d, ((2 + SMOOTHING * p0) / (2 + SMOOTHING)) / p0);
		assertThat(updated).isGreaterThanOrEqualTo(2);
		assertThat(score(converted)).isCloseTo(2 * conversionRatio + 2, within(1e-6));
		assertThat(score(anonymousOnly)).isZero();
		assertThat(score(courseAddOnly)).isEqualTo(1d);
		assertThat(score(untouched)).isZero();
	}

	@Test
	@DisplayName("랭킹만 있는 가게는 raw 점수(51 − 순위)에 배율을 곱한 값 — 배율은 두 분포의 같은 분위 비")
	void refresh_rankingIsScaledToBehaviorQuantile() {
		Long famousMenuId = jdbcTemplate.queryForObject("SELECT id FROM nationwide_famous_menu ORDER BY id LIMIT 1",
			Long.class);
		jdbcTemplate.update(
			"INSERT INTO nationwide_hot_store_ranking (nationwide_famous_menu_id, store_id, rank) VALUES (?, ?, 1)",
			famousMenuId, rankedOnly);

		storePopularityRepository.refreshAllPopularityScores(SMOOTHING, QUANTILE);

		// SQL 과 같은 정의로 배율을 다시 계산한다: 행동 점수(0 초과) 분위 ÷ 랭킹 점수(0 초과) 분위
		double scale = jdbcTemplate.queryForObject("""
				WITH rk AS (SELECT store_id, sum(greatest(0, 51 - rank))::float8 AS score
						FROM nationwide_hot_store_ranking GROUP BY store_id),
				vw AS (SELECT store_id, count(DISTINCT member_id)::float8 AS members FROM request_log
						WHERE event_type = 'STORE_VIEW' AND store_id IS NOT NULL AND member_id IS NOT NULL
						GROUP BY store_id),
				sv AS (SELECT store_id, count(*)::float8 AS cnt FROM saved_stores GROUP BY store_id),
				ca AS (SELECT cs.store_id, count(*)::float8 AS cnt
						FROM course_stores cs JOIN courses c ON c.id = cs.course_id
						WHERE c.member_id IS NOT NULL AND cs.deleted_at IS NULL GROUP BY cs.store_id),
				base AS (SELECT COALESCE(((SELECT sum(cnt) FROM sv) + (SELECT sum(cnt) FROM ca))
						/ NULLIF((SELECT sum(members) FROM vw), 0), 0) AS p0),
				behavior AS (SELECT st.id, COALESCE(rk.score, 0) AS rank_score,
						COALESCE(vw.members, 0) * CASE WHEN base.p0 > 0
							THEN LEAST(1.0, ((COALESCE(sv.cnt, 0) + COALESCE(ca.cnt, 0)) + ? * base.p0)
								/ (COALESCE(vw.members, 0) + ?) / base.p0) ELSE 1 END
						+ COALESCE(sv.cnt, 0) + COALESCE(ca.cnt, 0) AS score
					FROM store st LEFT JOIN rk ON rk.store_id = st.id LEFT JOIN vw ON vw.store_id = st.id
						LEFT JOIN sv ON sv.store_id = st.id LEFT JOIN ca ON ca.store_id = st.id CROSS JOIN base)
				SELECT (SELECT percentile_cont(?) WITHIN GROUP (ORDER BY score) FROM behavior WHERE score > 0)
					/ (SELECT percentile_cont(?) WITHIN GROUP (ORDER BY rank_score) FROM behavior WHERE rank_score > 0)
				""", Double.class, SMOOTHING, SMOOTHING, QUANTILE, QUANTILE);
		assertThat(scale).isBetween(0d, 1d);
		assertThat(score(rankedOnly)).isCloseTo(50 * scale, within(1e-6));
	}

	@Test
	@DisplayName("같은 데이터로 다시 돌리면 바뀐 행이 0 이다 — 주간 배치가 매번 45k행을 쓰지 않는다")
	void refresh_twice_updatesNothing() {
		storePopularityRepository.refreshAllPopularityScores(SMOOTHING, QUANTILE);

		assertThat(storePopularityRepository.refreshAllPopularityScores(SMOOTHING, QUANTILE)).isZero();
	}
}

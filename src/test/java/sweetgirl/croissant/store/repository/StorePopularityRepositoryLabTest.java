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
 * 랩 PG(운영 덤프)에서 인기도 갱신 SQL 을 실제로 돌린다. 신호가 하나도 없는 ACTIVE 가게 여덟을 골라 각각 다른 신호를 넣고, 회원 조회를
 * 사람 단위로 세는지 · 찜과 담기가 더해지는지 · 익명 조회 · 큐레이션 코스 · 삭제된 코스 · 삭제된 담기가 0 인지 · 랭킹이 배율을 타는지
 * 본다. p0 와 배율은 랩 전체 데이터에 좌우되므로 SQL 과 같은 정의로 다시 계산해 기대값을 만든다.
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
	/** SQL 의 signals · base 와 같은 정의 — 기대값 계산용 */
	private static final String SIGNALS_SQL = """
			WITH rk AS (SELECT store_id, sum(greatest(0, 51 - rank))::float8 AS score
					FROM nationwide_hot_store_ranking GROUP BY store_id),
			vw AS (SELECT store_id, count(DISTINCT member_id)::float8 AS members FROM request_log
					WHERE event_type = 'STORE_VIEW' AND store_id IS NOT NULL AND member_id IS NOT NULL
					AND response_status < 400
					GROUP BY store_id),
			sv AS (SELECT store_id, count(*)::float8 AS cnt FROM saved_stores GROUP BY store_id),
			ca AS (SELECT cs.store_id, count(DISTINCT c.member_id)::float8 AS cnt
					FROM course_stores cs JOIN courses c ON c.id = cs.course_id
					WHERE c.member_id IS NOT NULL AND c.deleted_at IS NULL AND cs.deleted_at IS NULL
				GROUP BY cs.store_id),
			signals AS (SELECT st.id, COALESCE(rk.score, 0) AS rank_score, COALESCE(vw.members, 0) AS members,
					COALESCE(sv.cnt, 0) + COALESCE(ca.cnt, 0) AS conv
				FROM store st LEFT JOIN rk ON rk.store_id = st.id LEFT JOIN vw ON vw.store_id = st.id
					LEFT JOIN sv ON sv.store_id = st.id LEFT JOIN ca ON ca.store_id = st.id
				WHERE st.status = 'ACTIVE'),
			base AS (SELECT COALESCE(sum(conv) / NULLIF(sum(members), 0), 0) AS p0 FROM signals),
			behavior AS (SELECT s.id, s.rank_score,
					s.members * CASE WHEN base.p0 > 0
						THEN LEAST(1.0, (s.conv + ? * base.p0) / NULLIF(s.members + ?, 0) / base.p0) ELSE 1 END
					+ s.conv AS score
				FROM signals s CROSS JOIN base)
			""";

	@Autowired
	private StorePopularityRepository storePopularityRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private List<Long> stores;
	private List<Long> members;

	@BeforeEach
	void pickFixtures() {
		stores = jdbcTemplate.queryForList("""
				SELECT s.id FROM store s
				WHERE s.status = 'ACTIVE'
					AND NOT EXISTS (SELECT 1 FROM nationwide_hot_store_ranking x WHERE x.store_id = s.id)
					AND NOT EXISTS (SELECT 1 FROM request_log x WHERE x.store_id = s.id)
					AND NOT EXISTS (SELECT 1 FROM saved_stores x WHERE x.store_id = s.id)
					AND NOT EXISTS (SELECT 1 FROM course_stores x WHERE x.store_id = s.id)
				ORDER BY s.id
				LIMIT 8
				""", Long.class);
		assertThat(stores).hasSize(8);
		members = jdbcTemplate.queryForList(
			"SELECT id FROM member WHERE deleted_at IS NULL ORDER BY id LIMIT 2", Long.class);
		assertThat(members).hasSize(2);
	}

	private void view(Long storeId, Long memberId, int status) {
		jdbcTemplate.update("""
				INSERT INTO request_log
					(member_id, http_method, endpoint, response_status, store_id, event_type, created_at)
				VALUES (?, 'GET', '/stores/' || ?, ?, ?, 'STORE_VIEW', NOW())
				""", memberId, storeId, status, storeId);
	}

	private Long course(Long ownerId, boolean deleted) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO courses (type, title, is_public, created_at, member_id, deleted_at)
				VALUES (?, '인기도 랩 픽스처', false, NOW(), ?, CASE WHEN ? THEN NOW() END)
				RETURNING id
				""", Long.class, ownerId == null ? "CURATED" : "CUSTOM", ownerId, deleted);
	}

	private void addToCourse(Long courseId, Long storeId, boolean deleted) {
		jdbcTemplate.update("""
				INSERT INTO course_stores (course_id, store_id, visit_order, created_at, deleted_at)
				VALUES (?, ?, 1, NOW(), CASE WHEN ? THEN NOW() END)
				""", courseId, storeId, deleted);
	}

	private double score(Long storeId) {
		Double value = jdbcTemplate.queryForObject("SELECT popularity_score FROM store WHERE id = ?", Double.class,
			storeId);
		return value != null ? value : 0d;
	}

	private double conversionBase() {
		return jdbcTemplate.queryForObject(SIGNALS_SQL + "SELECT p0 FROM base", Double.class, SMOOTHING, SMOOTHING);
	}

	private double rankingScale() {
		return jdbcTemplate.queryForObject(SIGNALS_SQL + """
				SELECT (SELECT percentile_cont(?) WITHIN GROUP (ORDER BY score) FROM behavior WHERE score > 0)
					/ (SELECT percentile_cont(?) WITHIN GROUP (ORDER BY rank_score) FROM behavior WHERE rank_score > 0)
				""", Double.class, SMOOTHING, SMOOTHING, QUANTILE, QUANTILE);
	}

	@Test
	@DisplayName("행동 점수 = 회원 조회 × min(1, 상대 전환율) + 찜 + 담은 회원 수 — 재조회 · 실패 응답 · 익명 · 큐레이션 · 삭제는 안 센다")
	void refresh_behaviorScore() {
		Long converted = stores.get(0);
		Long anonymousOnly = stores.get(1);
		Long courseAddOnly = stores.get(2);
		Long curatedOnly = stores.get(3);
		Long deletedCourseOnly = stores.get(4);
		Long deletedAddOnly = stores.get(5);
		Long untouched = stores.get(6);
		view(converted, members.get(0), 200);
		view(converted, members.get(1), 200);
		view(converted, members.get(0), 200); // 같은 회원의 두 번째 조회는 distinct 라 안 센다
		view(converted, members.get(1), 404); // 실패 응답은 조회가 아니다
		jdbcTemplate.update("INSERT INTO saved_stores (member_id, store_id, created_at) VALUES (?, ?, NOW())",
			members.get(0), converted);
		jdbcTemplate.update("INSERT INTO saved_stores (member_id, store_id, created_at) VALUES (?, ?, NOW())",
			members.get(1), converted);
		for (int i = 0; i < 5; i++) {
			view(anonymousOnly, null, 200);
		}
		Long owned = course(members.get(0), false);
		Long ownedTwice = course(members.get(0), false);
		addToCourse(owned, courseAddOnly, false);
		addToCourse(ownedTwice, courseAddOnly, false); // 같은 회원이 두 코스에 담아도 한 사람
		addToCourse(course(null, false), curatedOnly, false);
		addToCourse(course(members.get(1), true), deletedCourseOnly, false);
		addToCourse(owned, deletedAddOnly, true);

		int updated = storePopularityRepository.refreshAllPopularityScores(SMOOTHING, QUANTILE);

		double p0 = conversionBase();
		double conversionRatio = Math.min(1d, ((2 + SMOOTHING * p0) / (2 + SMOOTHING)) / p0);
		assertThat(updated).isGreaterThanOrEqualTo(2);
		assertThat(score(converted)).isCloseTo(2 * conversionRatio + 2, within(1e-6));
		assertThat(score(anonymousOnly)).isZero();
		assertThat(score(courseAddOnly)).isEqualTo(1d);
		assertThat(score(curatedOnly)).isZero();
		assertThat(score(deletedCourseOnly)).isZero();
		assertThat(score(deletedAddOnly)).isZero();
		assertThat(score(untouched)).isZero();
	}

	@Test
	@DisplayName("랭킹만 있는 가게는 raw 점수(51 − 순위)에 배율을 곱한 값 — 배율은 두 분포의 같은 분위 비, 랭킹이 비면 0")
	void refresh_rankingIsScaledToBehaviorQuantile() {
		Long rankedOnly = stores.get(7);
		Long famousMenuId = jdbcTemplate.queryForObject("SELECT id FROM nationwide_famous_menu ORDER BY id LIMIT 1",
			Long.class);
		jdbcTemplate.update(
			"INSERT INTO nationwide_hot_store_ranking (nationwide_famous_menu_id, store_id, rank) VALUES (?, ?, 1)",
			famousMenuId, rankedOnly);

		storePopularityRepository.refreshAllPopularityScores(SMOOTHING, QUANTILE);

		double scale = rankingScale();
		assertThat(scale).isBetween(0d, 1d);
		assertThat(score(rankedOnly)).isCloseTo(50 * scale, within(1e-6));

		jdbcTemplate.update("DELETE FROM nationwide_hot_store_ranking");
		storePopularityRepository.refreshAllPopularityScores(SMOOTHING, QUANTILE);

		assertThat(score(rankedOnly)).isZero();
	}

	@Test
	@DisplayName("같은 데이터로 다시 돌리면 바뀐 행이 0 이고, 갱신 뒤 p90 분위를 읽을 수 있다")
	void refresh_twice_updatesNothing() {
		storePopularityRepository.refreshAllPopularityScores(SMOOTHING, QUANTILE);

		assertThat(storePopularityRepository.refreshAllPopularityScores(SMOOTHING, QUANTILE)).isZero();
		assertThat(storePopularityRepository.findActivePopularityQuantile(0.9)).isGreaterThanOrEqualTo(0d);
	}
}

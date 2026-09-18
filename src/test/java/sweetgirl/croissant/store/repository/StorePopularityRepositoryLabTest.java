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
 * 랩 PG(운영 덤프)에서 인기도 갱신 SQL 을 실제로 돌린다. 신호가 하나도 없는 가게 넷을 골라 각각 다른 신호를 넣고, 회원 조회만 세는지 ·
 * 상대 전환율이 맞는지 · 익명 조회만 있는 가게가 0 인지 본다. p0 는 랩 전체 데이터에 좌우되므로 같은 정의로 다시 계산해 기대값을 만든다.
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

	@Autowired
	private StorePopularityRepository storePopularityRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Long converted;
	private Long anonymousOnly;
	private Long subscribedOnly;
	private Long untouched;
	private List<Long> members;

	@BeforeEach
	void pickFixtures() {
		List<Long> stores = jdbcTemplate.queryForList("""
				SELECT s.id FROM store s
				WHERE s.status = 'ACTIVE'
					AND NOT EXISTS (SELECT 1 FROM subscribed_store x WHERE x.store_id = s.id)
					AND NOT EXISTS (SELECT 1 FROM nationwide_hot_store_ranking x WHERE x.store_id = s.id)
					AND NOT EXISTS (SELECT 1 FROM request_log x WHERE x.store_id = s.id)
					AND NOT EXISTS (SELECT 1 FROM saved_stores x WHERE x.store_id = s.id)
					AND NOT EXISTS (SELECT 1 FROM course_stores x WHERE x.store_id = s.id)
				ORDER BY s.id
				LIMIT 4
				""", Long.class);
		assertThat(stores).hasSize(4);
		converted = stores.get(0);
		anonymousOnly = stores.get(1);
		subscribedOnly = stores.get(2);
		untouched = stores.get(3);
		members = jdbcTemplate.queryForList(
			"SELECT id FROM member WHERE deleted_at IS NULL ORDER BY id LIMIT 2", Long.class);
		assertThat(members).hasSize(2);
	}

	private void view(Long storeId, Long memberId) {
		jdbcTemplate.update(
			"""
						INSERT INTO request_log
					(member_id, http_method, endpoint, response_status, store_id, event_type, created_at)
						VALUES (?, 'GET', '/stores/' || ?, 200, ?, 'STORE_VIEW', NOW())
						""",
			memberId, storeId, storeId);
	}

	private double score(Long storeId) {
		Double value = jdbcTemplate.queryForObject("SELECT popularity_score FROM store WHERE id = ?", Double.class,
			storeId);
		return value != null ? value : 0d;
	}

	@Test
	@DisplayName("회원 조회 × 상대 전환율 — 익명 조회만 있는 가게는 0, 구독만 있으면 1, 아무것도 없으면 0")
	void refresh_countsMemberViewsWeightedByRelativeConversion() {
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
		jdbcTemplate.update("""
				INSERT INTO subscribed_store (id, member_id, store_id, created_at)
				VALUES ((SELECT COALESCE(MAX(id), 0) + 1 FROM subscribed_store), ?, ?, NOW())
				""", members.get(0), subscribedOnly);

		int updated = storePopularityRepository.refreshAllPopularityScores(SMOOTHING);

		double p0 = jdbcTemplate.queryForObject("""
				SELECT (SELECT count(*)::float8 FROM (
							SELECT store_id FROM saved_stores
							UNION ALL
							SELECT cs.store_id FROM course_stores cs JOIN courses c ON c.id = cs.course_id
							WHERE c.member_id IS NOT NULL AND cs.deleted_at IS NULL) x)
					/ (SELECT sum(members) FROM (SELECT count(DISTINCT member_id)::float8 AS members FROM request_log
							WHERE event_type = 'STORE_VIEW' AND store_id IS NOT NULL AND member_id IS NOT NULL
							GROUP BY store_id) v)
				""", Double.class);
		double expected = 2 * (((2 + SMOOTHING * p0) / (2 + SMOOTHING)) / p0);
		assertThat(updated).isGreaterThanOrEqualTo(2);
		assertThat(score(converted)).isCloseTo(expected, within(1e-6));
		assertThat(score(anonymousOnly)).isZero();
		assertThat(score(subscribedOnly)).isEqualTo(1d);
		assertThat(score(untouched)).isZero();
	}

	@Test
	@DisplayName("같은 데이터로 다시 돌리면 바뀐 행이 0 이다 — 주간 배치가 매번 45k행을 쓰지 않는다")
	void refresh_twice_updatesNothing() {
		storePopularityRepository.refreshAllPopularityScores(SMOOTHING);

		assertThat(storePopularityRepository.refreshAllPopularityScores(SMOOTHING)).isZero();
	}
}

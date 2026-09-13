package sweetgirl.croissant.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

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
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;
import sweetgirl.croissant.recommendation.repository.RecommendationCandidateJdbcRepository;

/**
 * 후보 SQL 을 랩 PG(운영 덤프 복제본, PostGIS)에서 돈다. H2 로는 ST_DWithin 을 검증할 수 없다.
 * 실행: {@code ./gradlew test -PlabTests}. 성수 중심(37.5446, 127.0557)은 서울 HOT_PLACE 와 같은 좌표다.
 */
@Tag("lab")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, RecommendationCandidateJdbcRepository.class})
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
class RecommendationCandidateJdbcRepositoryLabTest {

	private static final double SEONGSU_LAT = 37.5446;
	private static final double SEONGSU_LNG = 127.0557;
	private static final int RADIUS_M = 1_200;
	private static final int RING_M = 1_000;
	private static final int MAX_RADIUS_M = 3_000;

	@Autowired
	private RecommendationCandidateJdbcRepository candidateRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("상권 후보: 반경 안 ACTIVE 가게를 인기순으로, 제외 목록과 상한을 지킨다")
	void areaCandidates_withinRadius_popularityOrdered() {
		List<CandidateStoreRow> all = candidateRepository.findAreaCandidates(
			SEONGSU_LAT, SEONGSU_LNG, RADIUS_M, null, null, List.of(), 15);

		assertThat(all).isNotEmpty().hasSizeLessThanOrEqualTo(15);
		assertThat(all).allSatisfy(row -> assertThat(row.distanceM()).isLessThanOrEqualTo(RADIUS_M));
		assertThat(all).extracting(CandidateStoreRow::popularityScore)
			.isSortedAccordingTo((a, b) -> Double.compare(b, a));

		Long excluded = all.get(0).storeId();
		List<CandidateStoreRow> withoutFirst = candidateRepository.findAreaCandidates(
			SEONGSU_LAT, SEONGSU_LNG, RADIUS_M, null, null, List.of(excluded), 15);
		assertThat(withoutFirst).extracting(CandidateStoreRow::storeId).doesNotContain(excluded);
	}

	@Test
	@DisplayName("상권 후보: 반경이 좁아도 시군구·동이 맞으면 들어온다 — 소속 판정의 보조 규칙")
	void areaCandidates_regionRuleAddsStoresOutsideRadius() {
		List<CandidateStoreRow> byRegion = candidateRepository.findAreaCandidates(
			SEONGSU_LAT, SEONGSU_LNG, 1, "성동구", "성수동", List.of(), 50);

		assertThat(byRegion).isNotEmpty();
		assertThat(byRegion).anySatisfy(row -> assertThat(row.distanceM()).isGreaterThan(1));
	}

	@Test
	@DisplayName("앵커 후보: 3km 안에서 1km 링 안쪽부터, 링 안에서는 인기순이다")
	void anchorCandidates_ringFirstThenPopularity() {
		List<CandidateStoreRow> rows = candidateRepository.findAnchorCandidates(
			SEONGSU_LAT, SEONGSU_LNG, RING_M, MAX_RADIUS_M, List.of(), 30);

		assertThat(rows).hasSize(30);
		assertThat(rows).allSatisfy(row -> assertThat(row.distanceM()).isLessThanOrEqualTo(MAX_RADIUS_M));
		List<Integer> rings = rows.stream()
			.map(row -> (int)Math.ceil(row.distanceM() / RING_M))
			.toList();
		assertThat(rings).isSorted();
		// 같은 링 안에서는 인기순
		for (int i = 1; i < rows.size(); i++) {
			if (rings.get(i).equals(rings.get(i - 1))) {
				assertThat(rows.get(i).popularityScore()).isLessThanOrEqualTo(rows.get(i - 1).popularityScore());
			}
		}
	}

	@Test
	@DisplayName("칩 재료: 반경 안 가게 수와, 회원의 저장 가게가 반경 안에 있는지")
	void chipInputs_countAndSavedExists() {
		int count = candidateRepository.countActiveStoresWithin(SEONGSU_LAT, SEONGSU_LNG, RADIUS_M);
		assertThat(count).isGreaterThan(0);

		Map<String, Object> saved = jdbcTemplate.queryForMap("""
				SELECT ss.member_id, ST_Y(s.location::geometry) AS lat, ST_X(s.location::geometry) AS lng
				FROM saved_stores ss JOIN store s ON s.id = ss.store_id
				ORDER BY ss.id LIMIT 1
				""");
		Long memberId = ((Number)saved.get("member_id")).longValue();
		double lat = ((Number)saved.get("lat")).doubleValue();
		double lng = ((Number)saved.get("lng")).doubleValue();

		assertThat(candidateRepository.existsSavedStoreWithin(memberId, lat, lng, 100)).isTrue();
		// 남극점 근처엔 아무도 가게를 저장하지 않았다
		assertThat(candidateRepository.existsSavedStoreWithin(memberId, -89.0, 0.0, 100)).isFalse();
	}
}

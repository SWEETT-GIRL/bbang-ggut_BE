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
import sweetgirl.croissant.recommendation.entity.CourseArea;
import sweetgirl.croissant.recommendation.repository.AreaChipStats;
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;
import sweetgirl.croissant.recommendation.repository.CourseAreaRepository;
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
	/** 부산 중구 도장 칸 중심(V20260914174500 시드와 같은 좌표) */
	private static final double BUSAN_JUNGGU_LAT = 35.10407;
	private static final double BUSAN_JUNGGU_LNG = 129.03509;
	private static final int RADIUS_M = 1_200;
	private static final int RING_M = 1_000;
	private static final int MAX_RADIUS_M = 3_000;

	@Autowired
	private RecommendationCandidateJdbcRepository candidateRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private CourseAreaRepository courseAreaRepository;

	@Test
	@DisplayName("상권 후보: 반경 안 ACTIVE 가게를 인기순으로, 제외 목록과 상한을 지킨다")
	void areaCandidates_withinRadius_popularityOrdered() {
		List<CandidateStoreRow> all = candidateRepository.findAreaCandidates(
			SEONGSU_LAT, SEONGSU_LNG, RADIUS_M, null, null, null, List.of(), 15);

		assertThat(all).isNotEmpty().hasSizeLessThanOrEqualTo(15);
		assertThat(all).allSatisfy(row -> assertThat(row.distanceM()).isLessThanOrEqualTo(RADIUS_M));
		assertThat(all).extracting(CandidateStoreRow::popularityScore)
			.isSortedAccordingTo((a, b) -> Double.compare(b, a));

		Long excluded = all.get(0).storeId();
		List<CandidateStoreRow> withoutFirst = candidateRepository.findAreaCandidates(
			SEONGSU_LAT, SEONGSU_LNG, RADIUS_M, null, null, null, List.of(excluded), 15);
		assertThat(withoutFirst).extracting(CandidateStoreRow::storeId).doesNotContain(excluded);
	}

	@Test
	@DisplayName("상권 후보: 반경이 좁아도 시군구·동이 맞으면 들어온다 — 소속 판정의 보조 규칙")
	void areaCandidates_regionRuleAddsStoresOutsideRadius() {
		List<CandidateStoreRow> byRegion = candidateRepository.findAreaCandidates(
			SEONGSU_LAT, SEONGSU_LNG, 1, "서울", "성동구", "성수동", List.of(), 50);

		assertThat(byRegion).isNotEmpty();
		assertThat(byRegion).anySatisfy(row -> assertThat(row.distanceM()).isGreaterThan(1));
	}

	@Test
	@DisplayName("상권 후보: 시군구 이름이 같은 다른 시·도(대구·대전·인천 중구)는 들어오지 않는다")
	void areaCandidates_regionRuleIsScopedBySido() {
		List<CandidateStoreRow> busanJunggu = candidateRepository.findAreaCandidates(
			BUSAN_JUNGGU_LAT, BUSAN_JUNGGU_LNG, 1, "부산", "중구", null, List.of(), 3000);

		// 부산 중구 ACTIVE 가게는 120곳 안팎 — 전국 중구를 다 끌어오면 1,500곳이 넘고 대구 중구는 90km 밖이다
		assertThat(busanJunggu).hasSizeBetween(50, 400);
		assertThat(busanJunggu).allSatisfy(row -> assertThat(row.distanceM()).isLessThan(30_000));
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
	@DisplayName("칩 재료 한 문장: 상권마다 가게 수(후보와 같은 소속 규칙)와 내 저장 가게 유무를 같이 준다")
	void areaChipStats_oneQueryForAllChips() {
		Map<String, Object> saved = jdbcTemplate.queryForMap("""
				SELECT ss.member_id, ST_Y(s.location::geometry) AS lat, ST_X(s.location::geometry) AS lng
				FROM saved_stores ss JOIN store s ON s.id = ss.store_id WHERE s.status = 'ACTIVE'
				ORDER BY ss.id LIMIT 1
				""");
		Long memberId = ((Number)saved.get("member_id")).longValue();
		double savedLat = ((Number)saved.get("lat")).doubleValue();
		double savedLng = ((Number)saved.get("lng")).doubleValue();
		// 시드된 도장 칸("서울", "성수동")과 (sido, name) 이 겹치지 않게 이름을 구분한다
		CourseArea seongsu = courseAreaRepository.save(
			CourseArea.create("서울", "성수동(반경)", null, SEONGSU_LAT, SEONGSU_LNG, RADIUS_M, RADIUS_M, null, null, 1));
		// 반경 1m 인데 시군구·동이 맞아 들어오는 가게 — 칩 숫자가 후보 수와 같은 규칙로 센다
		CourseArea byRegion = courseAreaRepository.save(
			CourseArea.create("서울", "성수동(지역)", null, SEONGSU_LAT, SEONGSU_LNG, 1, RADIUS_M, "성동구", "성수동", 2));
		CourseArea aroundSaved = courseAreaRepository.save(
			CourseArea.create("서울", "저장 근처", null, savedLat, savedLng, 100, RADIUS_M, null, null, 3));
		CourseArea antarctica = courseAreaRepository.save(
			CourseArea.create("서울", "남극", null, -89.0, 0.0, 100, RADIUS_M, null, null, 4));
		// 시군구 이름이 전국에서 겹치는 칸 — 시·도를 안 보면 대구·대전·인천·울산 중구까지 세어 1,500곳이 넘는다
		CourseArea busanJunggu = courseAreaRepository.save(
			CourseArea.create("부산", "중구(랩)", null, BUSAN_JUNGGU_LAT, BUSAN_JUNGGU_LNG, 1, RADIUS_M, "중구", null, 5));

		Map<Long, AreaChipStats> stats = candidateRepository.findAreaChipStats(memberId,
			List.of(seongsu, byRegion, aroundSaved, antarctica, busanJunggu));

		assertThat(stats).hasSize(5);
		assertThat(stats.get(busanJunggu.getId()).storeCount()).isBetween(50, 400);
		assertThat(stats.get(seongsu.getId()).storeCount()).isGreaterThan(0);
		assertThat(stats.get(byRegion.getId()).storeCount()).isGreaterThan(0);
		assertThat(stats.get(aroundSaved.getId()).hasSavedStore()).isTrue();
		assertThat(stats.get(antarctica.getId()).storeCount()).isZero();
		assertThat(stats.get(antarctica.getId()).hasSavedStore()).isFalse();
	}
}

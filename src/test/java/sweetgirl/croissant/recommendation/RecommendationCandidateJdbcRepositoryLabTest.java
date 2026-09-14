package sweetgirl.croissant.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.config.JacksonConfig;
import sweetgirl.croissant.config.persistence.JpaConfig;
import sweetgirl.croissant.recommendation.config.RecommendationProperties;
import sweetgirl.croissant.recommendation.entity.CourseArea;
import sweetgirl.croissant.recommendation.repository.AreaChipStats;
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;
import sweetgirl.croissant.recommendation.repository.CourseAreaRepository;
import sweetgirl.croissant.recommendation.repository.RecommendationCandidateJdbcRepository;
import sweetgirl.croissant.recommendation.repository.TasteQuery;

/**
 * 후보 SQL 을 랩 PG(운영 덤프 복제본, PostGIS)에서 돈다. H2 로는 ST_DWithin 을 검증할 수 없다.
 * 실행: {@code ./gradlew test -PlabTests}. 성수 중심(37.5446, 127.0557)은 서울 HOT_PLACE 와 같은 좌표다.
 */
@Tag("lab")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, JacksonConfig.class, RecommendationProperties.class,
	RecommendationCandidateJdbcRepository.class})
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
	/** 기록이 없는 회원 id — bread_record 제외 규칙이 결과를 안 건드리게 */
	private static final long LAB_MEMBER = -1L;
	private static final int RADIUS_M = 1_200;
	private static final int RING_M = 1_000;
	private static final int MAX_RADIUS_M = 3_000;

	@Autowired
	private RecommendationCandidateJdbcRepository candidateRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private ObjectMapper objectMapper;
	@Autowired
	private CourseAreaRepository courseAreaRepository;

	@Test
	@DisplayName("상권 후보: 반경 안 ACTIVE 가게를 점수순으로, 제외 목록과 상한을 지킨다")
	void areaCandidates_withinRadius_popularityOrdered() {
		List<CandidateStoreRow> all = candidateRepository.findAreaCandidates(
			LAB_MEMBER, TasteQuery.empty(), SEONGSU_LAT, SEONGSU_LNG, RADIUS_M, null, null, null, List.of(), 15);

		assertThat(all).isNotEmpty().hasSizeLessThanOrEqualTo(15);
		assertThat(all).allSatisfy(row -> assertThat(row.distanceM()).isLessThanOrEqualTo(RADIUS_M));
		// #661: 정렬은 인기 점수가 아니라 점수(취향이 없으면 인기 prior 만 남는다)
		assertThat(all).extracting(CandidateStoreRow::score).isSortedAccordingTo((a, b) -> Double.compare(b, a));
		assertThat(all).allSatisfy(row -> assertThat(row.tasteFit()).isZero());

		Long excluded = all.get(0).storeId();
		List<CandidateStoreRow> withoutFirst = candidateRepository.findAreaCandidates(
			LAB_MEMBER, TasteQuery.empty(), SEONGSU_LAT, SEONGSU_LNG, RADIUS_M, null, null, null, List.of(excluded),
			15);
		assertThat(withoutFirst).extracting(CandidateStoreRow::storeId).doesNotContain(excluded);
	}

	@Test
	@DisplayName("상권 후보: 반경이 좁아도 시군구·동이 맞으면 들어온다 — 소속 판정의 보조 규칙")
	void areaCandidates_regionRuleAddsStoresOutsideRadius() {
		List<CandidateStoreRow> byRegion = candidateRepository.findAreaCandidates(
			LAB_MEMBER, TasteQuery.empty(), SEONGSU_LAT, SEONGSU_LNG, 1, "서울", "성동구", "성수동", List.of(), 50);

		assertThat(byRegion).isNotEmpty();
		assertThat(byRegion).anySatisfy(row -> assertThat(row.distanceM()).isGreaterThan(1));
	}

	@Test
	@DisplayName("상권 후보: 시군구 이름이 같은 다른 시·도(대구·대전·인천 중구)는 들어오지 않는다")
	void areaCandidates_regionRuleIsScopedBySido() {
		List<CandidateStoreRow> busanJunggu = candidateRepository.findAreaCandidates(
			LAB_MEMBER, TasteQuery.empty(), BUSAN_JUNGGU_LAT, BUSAN_JUNGGU_LNG, 1, "부산", "중구", null, List.of(), 3000);

		// 부산 중구 ACTIVE 가게는 120곳 안팎 — 전국 중구를 다 끌어오면 1,500곳이 넘고 대구 중구는 90km 밖이다
		assertThat(busanJunggu).hasSizeBetween(50, 400);
		assertThat(busanJunggu).allSatisfy(row -> assertThat(row.distanceM()).isLessThan(30_000));
	}

	@Test
	@DisplayName("점수식(#661): 취향 쿼리를 주면 코사인이 실리고 점수순이며, 풀의 취향 후보 수가 모든 행에 같이 실린다. 빵집다움 하한 미만은 없다")
	void areaCandidates_scoredByTaste() {
		TasteQuery saltyCrisp = new TasteQuery(0.8, 0.8, 0.3, 0.3, Map.of("카페형", 1.0), List.of("소금빵"));

		List<CandidateStoreRow> rows = candidateRepository.findAreaCandidates(
			LAB_MEMBER, saltyCrisp, SEONGSU_LAT, SEONGSU_LNG, RADIUS_M, null, null, null, List.of(), 15);

		assertThat(rows).hasSize(15);
		assertThat(rows).extracting(CandidateStoreRow::score).isSortedAccordingTo((a, b) -> Double.compare(b, a));
		assertThat(rows).allSatisfy(row -> assertThat(row.tasteFit()).isBetween(-1.0, 1.0));
		assertThat(rows).extracting(CandidateStoreRow::tasteCandidateCount)
			.containsOnly(rows.get(0).tasteCandidateCount());
		assertThat(rows.get(0).tasteCandidateCount()).isGreaterThanOrEqualTo(
			(int)rows.stream().filter(row -> row.tasteFit() > 0.2).count());
		assertThat(rows).anySatisfy(row -> assertThat(row.tasteFit()).isGreaterThan(0.2));
		assertThat(rows).allSatisfy(row -> assertThat(row.hasAxis() || row.tasteFit() == 0).isTrue());
		Long[] ids = rows.stream().map(CandidateStoreRow::storeId).toArray(Long[]::new);
		Integer belowBakery = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM store_profile WHERE store_id = ANY (?) AND bakery_score < 0.5", Integer.class,
			(Object)ids);
		assertThat(belowBakery).isZero();
	}

	@Test
	@DisplayName("점수식(#661): 종류·태그가 맞는 가게는 같은 가게라도 빈 쿼리 때보다 점수가 오른다")
	void areaCandidates_kindAndTagRaiseScore() {
		List<CandidateStoreRow> plain = candidateRepository.findAreaCandidates(
			LAB_MEMBER, TasteQuery.empty(), SEONGSU_LAT, SEONGSU_LNG, RADIUS_M, null, null, null, List.of(), 50);
		TasteQuery saltyLover = new TasteQuery(null, null, null, null, Map.of("카페형", 1.0), List.of("소금빵"));
		List<CandidateStoreRow> withKinds = candidateRepository.findAreaCandidates(
			LAB_MEMBER, saltyLover, SEONGSU_LAT, SEONGSU_LNG, RADIUS_M, null, null, null, List.of(), 50);

		Map<Long, Double> plainScore = plain.stream()
			.collect(Collectors.toMap(CandidateStoreRow::storeId, CandidateStoreRow::score));
		assertThat(withKinds).anySatisfy(row -> {
			assertThat(row.kinds()).contains("소금빵");
			assertThat(plainScore).containsKey(row.storeId());
			assertThat(row.score()).isGreaterThan(plainScore.get(row.storeId()));
		});
		// 축이 없는 쿼리라 코사인은 0
		assertThat(withKinds).allSatisfy(row -> assertThat(row.tasteFit()).isZero());
	}

	@Test
	@DisplayName("점수식(#661): 내가 기록한 가게는 기본으로 빠지고, 스위치를 끄면 점수만 깎인다")
	void areaCandidates_excludeRecordedStores() {
		List<Map<String, Object>> recorded = jdbcTemplate.queryForList("""
				SELECT br.member_id, br.store_id FROM bread_record br JOIN store s ON s.id = br.store_id
				JOIN store_profile p ON p.store_id = s.id AND p.bakery_score >= 0.5
				WHERE br.deleted_at IS NULL AND s.status = 'ACTIVE'
					AND ST_DWithin(s.location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
				LIMIT 1
				""", SEONGSU_LNG, SEONGSU_LAT, RADIUS_M);
		Assumptions.assumeFalse(recorded.isEmpty(), "성수 반경 안에 기록한 가게가 있는 회원이 없다");
		Long memberId = ((Number)recorded.get(0).get("member_id")).longValue();
		Long storeId = ((Number)recorded.get(0).get("store_id")).longValue();

		List<CandidateStoreRow> rows = candidateRepository.findAreaCandidates(
			memberId, TasteQuery.empty(), SEONGSU_LAT, SEONGSU_LNG, RADIUS_M, null, null, null, List.of(), 500);

		assertThat(rows).extracting(CandidateStoreRow::storeId).doesNotContain(storeId);

		// 스위치를 끄면(설정만 다른 리포지토리) 빠지지 않고 점수만 recorded-store-penalty 만큼 깎인다
		RecommendationProperties penaltyMode = new RecommendationProperties();
		penaltyMode.getScore().setExcludeRecordedStores(false);
		penaltyMode.getScore().setRecordedStorePenalty(0.3);
		RecommendationCandidateJdbcRepository penaltyRepository = new RecommendationCandidateJdbcRepository(
			new NamedParameterJdbcTemplate(jdbcTemplate), penaltyMode, objectMapper);
		Map<Long, Double> withPenalty = penaltyRepository.findAreaCandidates(
			memberId, TasteQuery.empty(), SEONGSU_LAT, SEONGSU_LNG, RADIUS_M, null, null, null, List.of(), 500)
			.stream().collect(Collectors.toMap(CandidateStoreRow::storeId, CandidateStoreRow::score));
		Map<Long, Double> plain = penaltyRepository.findAreaCandidates(
			LAB_MEMBER, TasteQuery.empty(), SEONGSU_LAT, SEONGSU_LNG, RADIUS_M, null, null, null, List.of(), 500)
			.stream().collect(Collectors.toMap(CandidateStoreRow::storeId, CandidateStoreRow::score));
		assertThat(withPenalty).containsKey(storeId);
		assertThat(withPenalty.get(storeId)).isCloseTo(plain.get(storeId) - 0.3, Offset.offset(1e-9));

		// 칩 가게 수도 같은 규칙 — 기록한 가게를 빼고 센다(PR #739 리뷰)
		CourseArea seongsuOnly = courseAreaRepository.save(CourseArea.create("서울", "성수동(칩)", "성수동(칩)", null,
			SEONGSU_LAT, SEONGSU_LNG, RADIUS_M, RADIUS_M, null, null, 9));
		AreaChipStats chip = candidateRepository.findAreaChipStats(memberId, List.of(seongsuOnly))
			.get(seongsuOnly.getId());
		assertThat(chip.storeCount()).isEqualTo(rows.size());

		// 재사용 자격 검사: 기록한 가게는 자격이 없고, 정상 후보는 있다
		Set<Long> unavailable = candidateRepository.findUnavailableStoreIds(memberId,
			List.of(storeId, rows.get(0).storeId()));
		assertThat(unavailable).containsExactly(storeId);
		assertThat(candidateRepository.findUnavailableStoreIds(LAB_MEMBER, List.of(storeId))).isEmpty();
	}

	@Test
	@DisplayName("앵커 후보: 3km 안에서 1km 링 안쪽부터, 링 안에서는 점수순이다")
	void anchorCandidates_ringFirstThenPopularity() {
		List<CandidateStoreRow> rows = candidateRepository.findAnchorCandidates(
			LAB_MEMBER, TasteQuery.empty(), SEONGSU_LAT, SEONGSU_LNG, RING_M, MAX_RADIUS_M, List.of(), 30);

		assertThat(rows).hasSize(30);
		assertThat(rows).allSatisfy(row -> assertThat(row.distanceM()).isLessThanOrEqualTo(MAX_RADIUS_M));
		List<Integer> rings = rows.stream()
			.map(row -> (int)Math.ceil(row.distanceM() / RING_M))
			.toList();
		assertThat(rings).isSorted();
		// 같은 링 안에서는 점수순(#661)
		for (int i = 1; i < rows.size(); i++) {
			if (rings.get(i).equals(rings.get(i - 1))) {
				assertThat(rows.get(i).score()).isLessThanOrEqualTo(rows.get(i - 1).score());
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
			CourseArea.create("서울", "성수동(반경)", "성수동(반경)", null, SEONGSU_LAT, SEONGSU_LNG, RADIUS_M, RADIUS_M, null,
				null, 1));
		// 반경 1m 인데 시군구·동이 맞아 들어오는 가게 — 칩 숫자가 후보 수와 같은 규칙로 센다
		CourseArea byRegion = courseAreaRepository.save(
			CourseArea.create("서울", "성수동(지역)", "성수동(지역)", null, SEONGSU_LAT, SEONGSU_LNG, 1, RADIUS_M, "성동구", "성수동",
				2));
		CourseArea aroundSaved = courseAreaRepository.save(
			CourseArea.create("서울", "저장 근처", "저장 근처", null, savedLat, savedLng, 100, RADIUS_M, null, null, 3));
		CourseArea antarctica = courseAreaRepository.save(
			CourseArea.create("서울", "남극", "남극", null, -89.0, 0.0, 100, RADIUS_M, null, null, 4));
		// 시군구 이름이 전국에서 겹치는 칸 — 시·도를 안 보면 대구·대전·인천·울산 중구까지 세어 1,500곳이 넘는다
		CourseArea busanJunggu = courseAreaRepository.save(
			CourseArea.create("부산", "중구(랩)", "중구(랩)", null, BUSAN_JUNGGU_LAT, BUSAN_JUNGGU_LNG, 1, RADIUS_M, "중구", null,
				5));

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

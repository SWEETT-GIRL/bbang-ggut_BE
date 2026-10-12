package sweetgirl.croissant.store.repository.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import sweetgirl.croissant.store.config.StoreSearchProperties;
import sweetgirl.croissant.store.service.search.SearchSort;
import sweetgirl.croissant.store.service.search.StoreSearchPlan;
import sweetgirl.croissant.store.service.search.StoreSearchSqlBuilder;

/**
 * 랩 PG(운영 덤프 복제본 + V20260818170000 마이그레이션 적용)에서 동적 SQL 이 케이스별로 실제 실행되는지 확인한다.
 * H2 로는 pg_bigm·PostGIS·생성 컬럼을 검증할 수 없어 통합 테스트로 분리했다.
 *
 * <p>실행: 랩 PG(docker-compose.lab-postgres.yml)를 띄운 뒤 {@code ./gradlew test -PlabTests}
 * (기본 빌드·CI 에서는 'lab' 태그가 제외된다). 접속은 시스템 프로퍼티 lab.db.url / lab.db.user / lab.db.password 로 바꿀 수 있다.
 */
@Tag("lab")
class StoreSearchJdbcRepositoryLabTest {

	private static StoreSearchJdbcRepository repository;
	private static final StoreSearchSqlBuilder BUILDER = new StoreSearchSqlBuilder(new StoreSearchProperties());
	private static final double MANGWON_LAT = 37.5563;
	private static final double MANGWON_LON = 126.9101;

	@BeforeAll
	static void connect() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
			System.getProperty("lab.db.url", "jdbc:postgresql://localhost:5432/soboro"),
			System.getProperty("lab.db.user", "sweetgirl"),
			System.getProperty("lab.db.password", "soboro-lab"));
		repository = new StoreSearchJdbcRepository(new NamedParameterJdbcTemplate(dataSource));
	}

	private static StoreSearchPlan plan(List<String> regions, List<String> names, String whole, boolean address) {
		return new StoreSearchPlan(regions, names, whole, address);
	}

	@Test
	@DisplayName("이름만: 부분일치 후보가 최종 점수 내림차순으로 나오고 맨 위는 접두 이상 매칭")
	void nameOnly() {
		List<StoreSearchRow> rows = repository.search(BUILDER.buildSearch(
			plan(List.of(), List.of("성심당"), "성심당", false), 16, 0, null, null, SearchSort.RELEVANCE));

		assertThat(rows).isNotEmpty();
		// "성심당 본점"(접두 80 × 인기도 보정)이 맨 위 — 정확 일치 가게가 없어도 접두 이상이어야 한다
		assertThat(rows.get(0).nameScore()).isGreaterThanOrEqualTo(StoreSearchSqlBuilder.NAME_PREFIX);
		for (int i = 1; i < rows.size(); i++) {
			assertThat(rows.get(i).score()).isLessThanOrEqualTo(rows.get(i - 1).score());
		}
		assertThat(rows).allSatisfy(row -> assertThat(row.matchedByName()).isTrue());
		assertThat(rows).allSatisfy(row -> assertThat(row.distanceMeters()).isNull());
	}

	@Test
	@DisplayName("지역 + 이름: 그 지역의 가게 중 이름 토큰을 가진 가게만")
	void regionAndName() {
		List<StoreSearchRow> rows = repository.search(BUILDER.buildSearch(
			plan(List.of("망원"), List.of("브릭"), "망원브릭", false), 16, 0, null, null, SearchSort.RELEVANCE));

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).matchedByRegion()).isTrue();
		// 전체("망원브릭")로는 0 이지만 토큰 "브릭"이 접두라 80
		assertThat(rows.get(0).nameScore()).isEqualTo(StoreSearchSqlBuilder.NAME_PREFIX);
	}

	@Test
	@DisplayName("지역만: 후보가 많아도(서울 1.5만) 한 페이지가 수십 ms 안에 나온다")
	void regionOnlyLargeRegion() {
		long startedAt = System.nanoTime();
		List<StoreSearchRow> rows = repository.search(BUILDER.buildSearch(
			plan(List.of("서울"), List.of(), "서울", false), 16, 0, MANGWON_LAT, MANGWON_LON, SearchSort.RELEVANCE));
		double elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0;

		assertThat(rows).hasSize(16);
		assertThat(rows).allSatisfy(row -> assertThat(row.matchedByRegion() || row.matchedByName()).isTrue());
		assertThat(elapsedMs).as("지역만 검색 e2e(ms)").isLessThan(200);
	}

	@Test
	@DisplayName("검색어 전체만(1글자 토큰): '빵'이 든 가게명")
	void wholeQueryOnly() {
		List<StoreSearchRow> rows = repository.search(BUILDER.buildSearch(
			plan(List.of(), List.of(), "빵", false), 16, 0, null, null, SearchSort.RELEVANCE));

		assertThat(rows).hasSize(16);
		assertThat(rows).allSatisfy(row -> assertThat(row.matchedByName()).isTrue());
	}

	@Test
	@DisplayName("지역 + 이름: 사전 alias 가 핫플 반경만 가리켜도(양산) 주소로 지역을 보완해 찾는다")
	void regionAndNameViaAddress() {
		List<StoreSearchRow> rows = repository.search(BUILDER.buildSearch(
			plan(List.of("양산"), List.of("트리마제"), "양산트리마제", false), 16, 0, null, null, SearchSort.RELEVANCE));

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).nameScore()).isEqualTo(StoreSearchSqlBuilder.NAME_CONTAINS);
		// 주소로만 잡힌 지역이라도 REGION 매칭으로 판정돼야 커버리지·matchedFields 에서 빠지지 않는다
		assertThat(rows.get(0).matchedByRegion()).isTrue();
	}

	@Test
	@DisplayName("주소 폴백: 사전에 없는 지역어를 주소로 찾는다")
	void addressFallback() {
		List<StoreSearchRow> rows = repository.search(BUILDER.buildSearch(
			plan(List.of(), List.of(), "천안시서북구", true), 16, 0, null, null, SearchSort.RELEVANCE));

		assertThat(rows).isNotEmpty();
		assertThat(rows).allSatisfy(row -> {
			assertThat(row.addressScore()).isEqualTo(StoreSearchSqlBuilder.ADDRESS_FALLBACK);
			assertThat(row.nameScore()).isZero();
		});
	}

	@Test
	@DisplayName("토큰 완화 ①: 지역으로 떨어지는 토큰도 이름 토큰으로 보면 어순과 무관하게 '어니언 성수'가 잡힌다")
	void anyOrderNameTokens() {
		// "성수 어니언" — 지역 '성수' ∩ 이름 '어니언' 은 0건(가게 주소가 성수 지역 집합 밖), 전체 "성수어니언"도 이름에 없다
		List<StoreSearchRow> relaxed = repository.search(BUILDER.buildSearch(
			plan(List.of(), List.of("성수", "어니언"), "성수어니언", false), 16, 0, null, null, SearchSort.RELEVANCE));
		List<StoreSearchRow> wholeName = repository.search(BUILDER.buildSearch(
			plan(List.of(), List.of(), "어니언성수", false), 16, 0, null, null, SearchSort.RELEVANCE));

		assertThat(wholeName).isNotEmpty();
		assertThat(relaxed).extracting(StoreSearchRow::storeId)
			.containsAll(wholeName.stream().map(StoreSearchRow::storeId).toList());
		assertThat(relaxed).allSatisfy(row -> assertThat(row.matchedByName()).isTrue());
	}

	@Test
	@DisplayName("토큰 완화 ②: 토큰별 후보 수를 상한까지만 센다 — 일반어는 상한, 없는 말은 0")
	void tokenCandidateCounts() {
		int cap = 15;
		List<StoreSearchTokenCountRow> counts = repository.countTokenCandidates(
			BUILDER.buildTokenCandidateCounts(List.of("성심당", "베이커리", "없는토큰zzqq"), cap));

		assertThat(counts).extracting(StoreSearchTokenCountRow::tokenIndex).containsExactlyInAnyOrder(0, 1, 2);
		assertThat(counts).filteredOn(count -> count.tokenIndex() == 0)
			.allSatisfy(count -> assertThat(count.candidateCount()).isBetween(1L, (long)cap));
		assertThat(counts).filteredOn(count -> count.tokenIndex() == 1)
			.allSatisfy(count -> assertThat(count.candidateCount()).isEqualTo(cap));
		assertThat(counts).filteredOn(count -> count.tokenIndex() == 2)
			.allSatisfy(count -> assertThat(count.candidateCount()).isZero());
	}

	@Test
	@DisplayName("거리순: 좌표가 있으면 거리 오름차순, 원거리 가게에서도 underflow 없이 점수가 계산된다")
	void distanceSortWithCoordinates() {
		// 성심당은 대전(망원에서 150km) — 거리 감쇠 exp() 클램프 검증
		List<StoreSearchRow> rows = repository.search(BUILDER.buildSearch(
			plan(List.of(), List.of("성심당"), "성심당", false), 16, 0, MANGWON_LAT, MANGWON_LON, SearchSort.DISTANCE));

		assertThat(rows).isNotEmpty();
		assertThat(rows).allSatisfy(row -> assertThat(row.distanceMeters()).isNotNull().isGreaterThan(100_000));
		for (int i = 1; i < rows.size(); i++) {
			assertThat(rows.get(i).distanceMeters()).isGreaterThanOrEqualTo(rows.get(i - 1).distanceMeters());
		}
		assertThat(rows).allSatisfy(row -> assertThat(row.score()).isGreaterThan(0));
	}

	@Test
	@DisplayName("인기순: popularity_score 내림차순, 페이지 오프셋 동작")
	void popularitySortAndOffset() {
		List<StoreSearchRow> firstPage = repository.search(BUILDER.buildSearch(
			plan(List.of("대구"), List.of(), "대구", false), 6, 0, null, null, SearchSort.POPULARITY));
		List<StoreSearchRow> secondPage = repository.search(BUILDER.buildSearch(
			plan(List.of("대구"), List.of(), "대구", false), 6, 5, null, null, SearchSort.POPULARITY));

		assertThat(firstPage).hasSize(6);
		for (int i = 1; i < firstPage.size(); i++) {
			assertThat(firstPage.get(i).popularityScore()).isLessThanOrEqualTo(firstPage.get(i - 1).popularityScore());
		}
		// size+1 로 읽었으니 1페이지 6번째 = 2페이지 첫 번째
		assertThat(secondPage.get(0).storeId()).isEqualTo(firstPage.get(5).storeId());
	}

	@Test
	@DisplayName("자동완성: 가게명·구명·지역 후보가 접두 우선으로 나온다")
	void autocomplete() {
		List<StoreAutocompleteRow> rows = repository.autocomplete(BUILDER.buildAutocomplete("성심", 10));

		assertThat(rows).isNotEmpty();
		assertThat(rows).extracting(StoreAutocompleteRow::type).contains("STORE");
		assertThat(rows).filteredOn(row -> row.tier() == 0)
			.allSatisfy(
				row -> assertThat(row.keyword().replaceAll("[^0-9a-zA-Z가-힣]", "").toLowerCase()).startsWith("성심"));
	}
}

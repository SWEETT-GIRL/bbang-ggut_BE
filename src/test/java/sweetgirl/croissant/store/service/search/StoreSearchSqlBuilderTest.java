package sweetgirl.croissant.store.service.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import sweetgirl.croissant.store.repository.search.SearchSqlQuery;

class StoreSearchSqlBuilderTest {

	private final StoreSearchSqlBuilder builder = new StoreSearchSqlBuilder();

	private static StoreSearchPlan plan(List<String> regions, List<String> names, String whole) {
		return new StoreSearchPlan(regions, names, whole, false);
	}

	private static int count(String haystack, String needle) {
		int count = 0;
		int index = 0;
		while ((index = haystack.indexOf(needle, index)) >= 0) {
			count++;
			index += needle.length();
		}
		return count;
	}

	@Test
	void regionAndName_intersectsRegionCandidatesWithEachNameToken() {
		SearchSqlQuery query = builder.buildSearch(plan(List.of("망원"), List.of("브릭", "베이글"), "망원브릭베이글"),
			16, 0, null, null, SearchSort.RELEVANCE);

		assertThat(query.sql()).contains("store_search_region", "search_region_alias");
		// 지역 후보 = 지역 매핑 ∪ 주소 부분일치("양산"처럼 사전 alias 가 핫플 반경만 가리키는 경우를 주소로 보완)
		assertThat(query.sql()).contains("s.address_normalized LIKE '%' || :region0 || '%'");
		assertThat(query.params().getValue("region0")).isEqualTo("망원");
		// (지역 ∪ 주소) ∩ 이름토큰1 ∩ 이름토큰2
		assertThat(count(query.sql(), "INTERSECT")).isEqualTo(2);
		assertThat(query.params().getValue("regionTokens")).isEqualTo(List.of("망원"));
		assertThat(query.params().getValue("name0")).isEqualTo("브릭");
		assertThat(query.params().getValue("name1")).isEqualTo("베이글");
		assertThat(query.params().getValue("whole")).isEqualTo("망원브릭베이글");
		// 이름 점수는 전체 + 토큰들 중 최고 계단
		assertThat(count(query.sql(), "s.name_normalized = :whole")).isEqualTo(1);
		assertThat(query.sql()).contains("s.name_normalized = :name0", "s.name_normalized = :name1");
		assertThat(query.sql()).contains("region_weight");
		// 지역 점수 = 매핑 weight×40 과 주소 매칭 20 중 큰 것 — 후보 규칙(매핑 ∪ 주소)과 판정 규칙을 일치시킨다
		assertThat(query.sql()).contains("greatest(COALESCE(rg.region_weight, 0) * :regionBase");
		assertThat(query.sql())
			.contains("CASE WHEN s.address_normalized LIKE '%' || :region0 || '%' THEN :regionAddress ELSE 0 END");
		assertThat(query.params().getValue("regionAddress")).isEqualTo(StoreSearchSqlBuilder.REGION_ADDRESS);
	}

	@Test
	void regionOnly_unionsRegionCandidatesWithWholeQueryNameMatch() {
		SearchSqlQuery query = builder.buildSearch(plan(List.of("성수동"), List.of(), "성수동"),
			16, 0, null, null, SearchSort.RELEVANCE);

		assertThat(query.sql()).contains("UNION (SELECT s.id FROM store s");
		assertThat(query.sql()).doesNotContain("INTERSECT");
		assertThat(query.sql()).contains("LIKE '%' || :whole || '%'");
		assertThat(query.params().getValue("regionTokens")).isEqualTo(List.of("성수동"));
	}

	@Test
	void nameOnly_intersectsEachTokenAcrossNameAndAlias() {
		SearchSqlQuery query = builder.buildSearch(plan(List.of(), List.of("우드", "브릭"), "우드앤브릭"),
			16, 0, null, null, SearchSort.RELEVANCE);

		assertThat(count(query.sql(), "INTERSECT")).isEqualTo(1);
		assertThat(query.sql()).contains("store_name_alias a WHERE a.alias_normalized LIKE '%' || :name0 || '%'");
		assertThat(query.sql()).doesNotContain("store_search_region ssr");
		assertThat(query.sql()).contains("0 AS region_score");
	}

	@Test
	void wholeQueryOnly_matchesNameOrAliasWithWholeQuery() {
		// 모든 토큰이 1글자("빵")라 이름 토큰이 없을 때
		SearchSqlQuery query = builder.buildSearch(plan(List.of(), List.of(), "빵"),
			16, 0, null, null, SearchSort.RELEVANCE);

		assertThat(query.sql()).contains("WITH cand AS (SELECT s.id FROM store s WHERE s.status = 'ACTIVE'"
			+ " AND s.name_normalized LIKE '%' || :whole || '%'");
		assertThat(query.sql()).doesNotContain("INTERSECT");
		assertThat(query.params().getValue("whole")).isEqualTo("빵");
	}

	@Test
	void addressFallback_usesAddressOnlyAndFixedScore() {
		SearchSqlQuery query = builder.buildSearch(new StoreSearchPlan(List.of("천안시"), List.of("서북구"), "천안시서북구", true),
			16, 0, null, null, SearchSort.RELEVANCE);

		assertThat(query.sql()).contains("s.address_normalized LIKE '%' || :whole || '%'");
		assertThat(query.sql()).doesNotContain("store_search_region", "store_name_alias", "INTERSECT");
		assertThat(query.sql()).contains(":addressFallback AS address_score", "0 AS name_score", "0 AS alias_score");
		assertThat(query.params().getValue("addressFallback"))
			.isEqualTo(StoreSearchSqlBuilder.ADDRESS_FALLBACK);
	}

	@Test
	void coordinates_addDistanceAndClampedGaussDecay() {
		SearchSqlQuery query = builder.buildSearch(plan(List.of(), List.of("성심당"), "성심당"),
			16, 0, 37.5, 127.0, SearchSort.RELEVANCE);

		assertThat(query.sql())
			.contains("(s.location <-> ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography) AS distance_m");
		// 먼 가게에서 exp() underflow 가 나지 않도록 지수를 자른다
		assertThat(query.sql()).contains("exp(-least(power(greatest(0, t.distance_m - :gaussOffset), 2)"
			+ " / :gaussTwoSigmaSquared, :gaussMaxExponent))");
		assertThat(query.params().getValue("lat")).isEqualTo(37.5);
		assertThat(query.params().getValue("lon")).isEqualTo(127.0);
		assertThat(query.params().getValue("gaussMaxExponent")).isEqualTo(700.0);
	}

	@Test
	void noCoordinates_nullDistanceAndNoDecay() {
		SearchSqlQuery query = builder.buildSearch(plan(List.of(), List.of("성심당"), "성심당"),
			16, 0, null, null, SearchSort.DISTANCE);

		assertThat(query.sql()).contains("CAST(NULL AS double precision) AS distance_m");
		assertThat(query.sql()).doesNotContain("<->", "exp(");
		// 좌표 없는 거리순은 정확도순으로 내려간다
		assertThat(query.sql()).contains("ORDER BY score DESC, t.popularity_score DESC, t.id ASC");
	}

	@Test
	void sortVariants_orderByDistanceOrPopularityFirst() {
		SearchSqlQuery distance = builder.buildSearch(plan(List.of(), List.of("성심당"), "성심당"),
			16, 0, 37.5, 127.0, SearchSort.DISTANCE);
		SearchSqlQuery popularity = builder.buildSearch(plan(List.of(), List.of("성심당"), "성심당"),
			16, 32, null, null, SearchSort.POPULARITY);

		assertThat(distance.sql()).contains("ORDER BY t.distance_m ASC NULLS LAST, score DESC, t.id ASC");
		assertThat(popularity.sql()).contains("ORDER BY t.popularity_score DESC, score DESC, t.id ASC");
		assertThat(popularity.params().getValue("limit")).isEqualTo(16);
		assertThat(popularity.params().getValue("offset")).isEqualTo(32);
	}

	@Test
	void autocomplete_unionsStoreNameAliasAndRegionSources() {
		SearchSqlQuery query = builder.buildAutocomplete("성심", 24);

		assertThat(query.sql()).contains("'STORE' AS type", "'REGION' AS type", "store_name_alias a",
			"search_region_alias ra");
		assertThat(count(query.sql(), "UNION ALL")).isEqualTo(2);
		// 접두 우선 tier
		assertThat(query.sql()).contains("CASE WHEN s.name_normalized LIKE :q || '%' THEN 0 ELSE 1 END AS tier");
		assertThat(query.params().getValue("q")).isEqualTo("성심");
		assertThat(query.params().getValue("limit")).isEqualTo(24);
	}
}

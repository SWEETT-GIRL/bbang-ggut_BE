package sweetgirl.croissant.store.service.search;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.store.config.StoreSearchProperties;
import sweetgirl.croissant.store.repository.search.SearchSqlQuery;

/**
 * {@link StoreSearchPlan} → 검색/자동완성 SQL. 사용자 입력은 전부 바인드 파라미터로만 들어간다.
 *
 * <p><b>후보(cand) → 점수 → 정렬</b> 3단 구조.
 * <ul>
 *   <li>후보: 조건별로 인덱스를 탈 수 있는 단순 SELECT 를 UNION/INTERSECT 로 합친다.
 *       (`name LIKE … OR EXISTS(alias …)` 한 덩어리는 인덱스를 못 타 seq scan 이 되는 것을 랩에서 확인)</li>
 *   <li>점수: 가게명 정확 100 / 접두 80 / 포함 60, 구명은 같은 계단 × 0.75, 지역은 40 × 매핑 weight, 주소 폴백 20.
 *       최종 = 텍스트 × (1 + 0.3·ln(1+인기도) + 거리 gauss).</li>
 *   <li>정렬: relevance = 점수, distance = 거리 후 점수, popularity = 인기도 후 점수.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class StoreSearchSqlBuilder {

	private final StoreSearchProperties storeSearchProperties;

	// 텍스트 점수 계단 (설명 가능하게 정수로 둔다)
	public static final int NAME_EXACT = 100;
	public static final int NAME_PREFIX = 80;
	public static final int NAME_CONTAINS = 60;
	static final int ALIAS_EXACT = 75;
	static final int ALIAS_PREFIX = 60;
	static final int ALIAS_CONTAINS = 45;
	static final int REGION_BASE = 40;
	// 지역 토큰이 사전 매핑엔 없고 주소에만 있는 가게("양산 트리마제"의 양산시 가게)의 지역 점수 — 매핑(weight×40)보다 낮은 계단
	static final int REGION_ADDRESS = 20;
	public static final int ADDRESS_FALLBACK = 20;
	// 인기도 보정 계수는 StoreSearchProperties.popularityFactor — log1p 로 인기 폭주 가게의 과도한 보정을 막는다
	// 거리 gauss — scale 3km, offset 500m, decay 0.5 → 2σ² = scale² / ln(1/decay)
	static final double GAUSS_OFFSET_METERS = 500.0;
	static final double GAUSS_TWO_SIGMA_SQUARED = 3000.0 * 3000.0 / Math.log(2.0);
	// exp(-x) 의 x 상한. double 은 exp(-745) 아래에서 0 이 되는데 PostgreSQL 은 이를 underflow 오류로 취급하므로 여유 있게 자른다.
	static final double GAUSS_MAX_EXPONENT = 700.0;

	public SearchSqlQuery buildSearch(StoreSearchPlan plan, int limit, int offset,
			Double latitude, Double longitude, SearchSort sort) {
		MapSqlParameterSource params = new MapSqlParameterSource();
		boolean hasCoordinates = latitude != null && longitude != null;
		String candidateSql = candidateSql(plan, params);

		StringBuilder sql = new StringBuilder();
		sql.append("WITH cand AS (").append(candidateSql).append(")\n");
		sql.append("SELECT t.id, t.popularity_score, t.name_score, t.alias_score, t.region_score, t.address_score,\n");
		sql.append("       t.distance_m,\n");
		sql.append("       (greatest(t.name_score, t.alias_score) + t.region_score + t.address_score)\n");
		sql.append("       * (1 + :popularityFactor * ln(1 + t.popularity_score) + ");
		if (hasCoordinates) {
			// 지수는 700 에서 자른다 — 150km 밖 가게처럼 exp(-1700) 이 되면 PostgreSQL 이 underflow 오류를 낸다(0 이 아니라 에러).
			sql.append("exp(-least(power(greatest(0, t.distance_m - :gaussOffset), 2) / :gaussTwoSigmaSquared,"
				+ " :gaussMaxExponent))");
			params.addValue("gaussOffset", GAUSS_OFFSET_METERS);
			params.addValue("gaussTwoSigmaSquared", GAUSS_TWO_SIGMA_SQUARED);
			params.addValue("gaussMaxExponent", GAUSS_MAX_EXPONENT);
		} else {
			sql.append("0");
		}
		sql.append(") AS score\n");
		sql.append("FROM (\n");
		sql.append("  SELECT s.id, s.popularity_score,\n");
		sql.append("         ").append(nameScoreExpr(plan, params)).append(" AS name_score,\n");
		sql.append("         ").append(aliasScoreExpr(plan, params)).append(" AS alias_score,\n");
		sql.append("         ").append(regionScoreExpr(plan, params)).append(" AS region_score,\n");
		sql.append("         ").append(plan.addressFallback() ? ":addressFallback" : "0")
			.append(" AS address_score,\n");
		if (hasCoordinates) {
			// geography <-> 는 구면 거리(m). ST_Distance(회전타원체)보다 8배쯤 싸고(2.7k 후보 2ms vs 18ms) 오차는 원거리 0.3% 수준이라
			// 거리 감쇠·거리순 정렬에는 충분하다.
			sql.append(
				"         (s.location <-> ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography) AS distance_m\n");
			params.addValue("lat", latitude);
			params.addValue("lon", longitude);
		} else {
			sql.append("         CAST(NULL AS double precision) AS distance_m\n");
		}
		sql.append("  FROM store s\n");
		sql.append("  JOIN cand ON cand.id = s.id\n");
		if (plan.hasRegionTokens() && !plan.addressFallback()) {
			sql.append("  LEFT JOIN (SELECT ssr.store_id, max(ssr.weight) AS region_weight\n");
			sql.append("             FROM store_search_region ssr\n");
			sql.append("             JOIN search_region_alias ra ON ra.region_id = ssr.region_id\n");
			sql.append("             WHERE ra.normalized_alias IN (:regionTokens)\n");
			sql.append("             GROUP BY ssr.store_id) rg ON rg.store_id = s.id\n");
		}
		if (plan.addressFallback()) {
			params.addValue("addressFallback", ADDRESS_FALLBACK);
		}
		sql.append("  WHERE s.status = 'ACTIVE'\n");
		sql.append(") t\n");
		sql.append(orderBy(sort, hasCoordinates));
		sql.append("LIMIT :limit OFFSET :offset");
		params.addValue("popularityFactor", storeSearchProperties.getPopularityFactor());
		params.addValue("limit", limit);
		params.addValue("offset", offset);
		return new SearchSqlQuery(sql.toString(), params);
	}

	// 후보 집합. 지역·이름 토큰·전체 검색어를 인덱스 친화적인 단순 SELECT 로 만들고 집합 연산으로 합친다.
	private String candidateSql(StoreSearchPlan plan, MapSqlParameterSource params) {
		if (plan.addressFallback()) {
			params.addValue("whole", plan.wholeQuery());
			return "SELECT s.id FROM store s WHERE s.status = 'ACTIVE'"
				+ " AND s.address_normalized LIKE '%' || :whole || '%'";
		}
		if (plan.hasWholeQuery()) {
			params.addValue("whole", plan.wholeQuery());
		}
		List<String> nameParts = new ArrayList<>();
		for (int i = 0; i < plan.nameTokens().size(); i++) {
			String key = "name" + i;
			params.addValue(key, plan.nameTokens().get(i));
			nameParts.add(nameOrAliasContains(key));
		}
		if (plan.hasRegionTokens()) {
			params.addValue("regionTokens", plan.regionTokens());
			// 지역 후보 = 지역 매핑 가게 ∪ 주소에 지역 토큰이 든 가게. 사전 alias 는 핫플/역 반경으로 잡혀 있어 "양산"이 양산시
			// 전체가 아니라 양산 핫플 근처 40곳만 가리키는 식이다 — 주소 bigm 으로 그 밖의 가게를 같이 본다.
			List<String> regionParts = new ArrayList<>();
			regionParts.add("SELECT DISTINCT ssr.store_id AS id FROM store_search_region ssr "
				+ "JOIN search_region_alias ra ON ra.region_id = ssr.region_id"
				+ " WHERE ra.normalized_alias IN (:regionTokens)");
			for (int i = 0; i < plan.regionTokens().size(); i++) {
				String key = "region" + i;
				params.addValue(key, plan.regionTokens().get(i));
				regionParts
					.add("SELECT s.id FROM store s WHERE s.status = 'ACTIVE' AND s.address_normalized LIKE '%' || :"
						+ key + " || '%'");
			}
			String regionCand = String.join("\n   UNION ", regionParts);
			if (!nameParts.isEmpty()) {
				// 지역 + 이름: 그 지역의 가게 중 이름 토큰을 모두 가진 가게만 (flood 제거)
				String intersected = "(" + regionCand + ")\nINTERSECT ("
					+ String.join(")\nINTERSECT (", nameParts) + ")";
				// 다만 검색어 전체가 가게 이름인 경우를 교집합이 삼키면 안 된다 — "어니언 성수"는
				// '성수'가 지역으로 떨어지는데, 그 가게 주소는 성동구 아차산로9길이라 지역 집합에 없다.
				// 이름이 통째로 맞는 가게는 지역과 무관하게 건진다 (2026-09-10 실측 버그).
				if (plan.hasWholeQuery()) {
					return "(" + intersected + ")\nUNION (" + nameOrAliasContains("whole") + ")";
				}
				return intersected;
			}
			// 지역만: 그 지역 가게 ∪ 이름이 검색어를 담은 가게 (지역명이 든 가게명도 노출)
			if (plan.hasWholeQuery()) {
				return "(" + regionCand + ")\nUNION (" + nameOrAliasContains("whole") + ")";
			}
			return regionCand;
		}
		if (!nameParts.isEmpty()) {
			// 이름만: 토큰마다 AND ("우드 앤 브릭" → 세 토큰 모두 포함)
			return "(" + String.join(")\nINTERSECT (", nameParts) + ")";
		}
		// 분류된 토큰이 없으면(전부 1글자 등) 검색어 전체로 부분일치
		return nameOrAliasContains("whole");
	}

	/**
	 * 토큰 완화 폴백용 — 토큰마다 "이름/구명에 그 토큰이 든 가게" 수를 한 번에 센다. 가장 구체적인 토큰을 고르는 데만 쓰므로
	 * cap 에서 세기를 멈춘다('베이커리' 같은 일반어의 수천 건을 끝까지 읽지 않는다).
	 */
	public SearchSqlQuery buildTokenCandidateCounts(List<String> tokens, int cap) {
		MapSqlParameterSource params = new MapSqlParameterSource().addValue("cap", cap);
		List<String> counts = new ArrayList<>();
		for (int i = 0; i < tokens.size(); i++) {
			String key = "name" + i;
			params.addValue(key, tokens.get(i));
			counts.add("SELECT " + i + " AS token_index, count(*) AS candidate_count FROM (SELECT u.id FROM (\n"
				+ nameOrAliasContains(key) + ") u LIMIT :cap) c");
		}
		return new SearchSqlQuery(String.join("\nUNION ALL\n", counts), params);
	}

	private String nameOrAliasContains(String paramKey) {
		return "SELECT s.id FROM store s WHERE s.status = 'ACTIVE' AND s.name_normalized LIKE '%' || :" + paramKey
			+ " || '%'\n"
			+ "   UNION SELECT a.store_id AS id FROM store_name_alias a WHERE a.alias_normalized LIKE '%' || :"
			+ paramKey + " || '%'";
	}

	// 지역 점수 = 매핑 weight × 40 과 "주소에 지역 토큰이 있음" 20 중 큰 것. 후보를 지역 매핑 ∪ 주소로 뽑았으니 판정도 같은 규칙이어야
	// 주소로만 잡힌 가게가 REGION 매칭·커버리지에서 빠지지 않는다.
	private String regionScoreExpr(StoreSearchPlan plan, MapSqlParameterSource params) {
		if (!plan.hasRegionTokens() || plan.addressFallback()) {
			return "0";
		}
		params.addValue("regionBase", REGION_BASE);
		params.addValue("regionAddress", REGION_ADDRESS);
		List<String> addressHits = new ArrayList<>();
		for (int i = 0; i < plan.regionTokens().size(); i++) {
			addressHits.add("s.address_normalized LIKE '%' || :region" + i + " || '%'");
		}
		return "greatest(COALESCE(rg.region_weight, 0) * :regionBase,\n"
			+ "                  CASE WHEN " + String.join(" OR ", addressHits) + " THEN :regionAddress ELSE 0 END)";
	}

	// 이름 점수 = 검색어 전체와 각 이름 토큰 중 가장 높은 계단. "망원 브릭"은 전체("망원브릭")로는 0이지만 토큰 "브릭"이
	// "브릭베이글"의 접두라 80점 — 지역+이름 검색에서 이름이 실제로 걸린 가게가 위로 온다.
	private String nameScoreExpr(StoreSearchPlan plan, MapSqlParameterSource params) {
		if (!plan.hasWholeQuery() || plan.addressFallback()) {
			return "0";
		}
		params.addValue("nameExact", NAME_EXACT);
		params.addValue("namePrefix", NAME_PREFIX);
		params.addValue("nameContains", NAME_CONTAINS);
		List<String> tiers = new ArrayList<>();
		for (String key : scoreTermKeys(plan)) {
			tiers.add(tierExpr("s.name_normalized", key, "nameExact", "namePrefix", "nameContains"));
		}
		return "greatest(" + String.join(",\n                  ", tiers) + ")";
	}

	// 구명 점수 = 그 가게의 구명 중 검색어(전체·토큰)에 걸린 것의 최고 계단. 후보 행마다 store_id 인덱스로 구명(1.7k 행)을 찾는
	// 상관 서브쿼리가, "걸린 구명을 모아 LEFT JOIN" 보다 빨랐다(2.7k 후보: 20ms vs 28ms — 조인 쪽은 행수 추정이 1로 빗나가
	// 중첩 루프 × Materialize 로 풀린다).
	private String aliasScoreExpr(StoreSearchPlan plan, MapSqlParameterSource params) {
		if (!plan.hasWholeQuery() || plan.addressFallback()) {
			return "0";
		}
		params.addValue("aliasExact", ALIAS_EXACT);
		params.addValue("aliasPrefix", ALIAS_PREFIX);
		params.addValue("aliasContains", ALIAS_CONTAINS);
		List<String> tiers = new ArrayList<>();
		List<String> filters = new ArrayList<>();
		for (String key : scoreTermKeys(plan)) {
			tiers.add(tierExpr("a.alias_normalized", key, "aliasExact", "aliasPrefix", "aliasContains"));
			filters.add("a.alias_normalized LIKE '%' || :" + key + " || '%'");
		}
		return "COALESCE((SELECT max(greatest(" + String.join(", ", tiers) + "))\n"
			+ "                   FROM store_name_alias a WHERE a.store_id = s.id AND (" + String.join(" OR ", filters)
			+ ")), 0)";
	}

	// 점수 계산에 쓰는 검색어 파라미터 키: 전체(whole) + 이름 토큰(name0, name1 …). 토큰 파라미터는 candidateSql 이 이미 바인딩했다.
	private List<String> scoreTermKeys(StoreSearchPlan plan) {
		List<String> keys = new ArrayList<>();
		keys.add("whole");
		for (int i = 0; i < plan.nameTokens().size(); i++) {
			keys.add("name" + i);
		}
		return keys;
	}

	private String tierExpr(String column, String paramKey, String exactKey, String prefixKey, String containsKey) {
		return "CASE WHEN " + column + " = :" + paramKey + " THEN :" + exactKey
			+ " WHEN " + column + " LIKE :" + paramKey + " || '%' THEN :" + prefixKey
			+ " WHEN " + column + " LIKE '%' || :" + paramKey + " || '%' THEN :" + containsKey
			+ " ELSE 0 END";
	}

	private String orderBy(SearchSort sort, boolean hasCoordinates) {
		if (sort == SearchSort.DISTANCE && hasCoordinates) {
			return "ORDER BY t.distance_m ASC NULLS LAST, score DESC, t.id ASC\n";
		}
		if (sort == SearchSort.POPULARITY) {
			return "ORDER BY t.popularity_score DESC, score DESC, t.id ASC\n";
		}
		return "ORDER BY score DESC, t.popularity_score DESC, t.id ASC\n";
	}

	/**
	 * 자동완성 후보. 가게명·구명·지역 alias 각각에서 "포함" 매칭을 접두 우선·인기도순으로 최대 limit 개씩 뽑는다.
	 */
	public SearchSqlQuery buildAutocomplete(String normalizedQuery, int limitPerSource) {
		MapSqlParameterSource params = new MapSqlParameterSource()
			.addValue("q", normalizedQuery)
			.addValue("limit", limitPerSource);
		String sql = """
				(SELECT s.name AS keyword, 'STORE' AS type, s.id AS store_id,
					CASE WHEN s.name_normalized LIKE :q || '%' THEN 0 ELSE 1 END AS tier, s.popularity_score AS pop,
					s.trending_baseline AS steady,
					CAST(NULL AS double precision) AS center_lat, CAST(NULL AS double precision) AS center_lng,
					CAST(NULL AS integer) AS radius_meters, CAST(NULL AS varchar) AS parent_name
				FROM store s
				WHERE s.status = 'ACTIVE' AND s.name_normalized LIKE '%' || :q || '%'
				ORDER BY tier, steady DESC, pop DESC, s.id LIMIT :limit)
				UNION ALL
				(SELECT a.alias AS keyword, 'STORE' AS type, a.store_id,
					CASE WHEN a.alias_normalized LIKE :q || '%' THEN 0 ELSE 1 END AS tier, s.popularity_score AS pop,
					s.trending_baseline AS steady,
					CAST(NULL AS double precision) AS center_lat, CAST(NULL AS double precision) AS center_lng,
					CAST(NULL AS integer) AS radius_meters, CAST(NULL AS varchar) AS parent_name
				FROM store_name_alias a JOIN store s ON s.id = a.store_id
				WHERE s.status = 'ACTIVE' AND a.alias_normalized LIKE '%' || :q || '%'
				ORDER BY tier, steady DESC, pop DESC, a.id LIMIT :limit)
				UNION ALL
				-- 지역 후보는 좌표·반경·부모 이름을 함께 준다.
				-- 좌표·반경: 고르면 지도가 그 자리로 가고 반경만큼 당긴다 — 앱이 동네 이름으로 추측하지 않아도 된다.
				-- 부모 이름: '중동'이 열세 곳이라 이것 없이는 어느 중동인지 고를 수가 없다.
				(SELECT ra.alias AS keyword, 'REGION' AS type, CAST(NULL AS bigint) AS store_id,
					CASE WHEN ra.normalized_alias LIKE :q || '%' THEN 0 ELSE 1 END AS tier, 0 AS pop,
					CAST(0 AS double precision) AS steady,
					r.center_lat, r.center_lng, r.radius_meters, parent.name AS parent_name
				FROM search_region_alias ra
				JOIN search_region r ON r.id = ra.region_id
				LEFT JOIN search_region parent ON parent.id = r.parent_region_id
				WHERE r.is_active = TRUE AND ra.normalized_alias LIKE '%' || :q || '%'
				ORDER BY tier, ra.is_primary DESC, r.display_order, ra.id LIMIT :limit)
				""";
		return new SearchSqlQuery(sql, params);
	}
}

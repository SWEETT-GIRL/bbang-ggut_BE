package sweetgirl.croissant.recommendation.repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.recommendation.config.RecommendationProperties;
import sweetgirl.croissant.recommendation.entity.CourseArea;

/**
 * 추천 후보 SQL (설계 §5.3 · §5.6 · #661). PostGIS 반경 조회라 H2 로는 못 돌린다 — 검증은 랩 테스트(@Tag lab).
 *
 * 점수 계산은 인라인 SQL 로 둔다 — PL/pgSQL 함수로 빼면 행마다 호출 비용이 붙는다(스키마 노션 §8.1).
 * score = cos·코사인(4축) + tag·태그 일치 + prior·인기 prior + kind·종류 겹침 (가중치는 RecommendationProperties.Score).
 * 후보는 store_profile 이 있고 bakery_score 가 하한 이상인 ACTIVE 가게만이다 — 프로필이 없는 가게(11%)는 빠진다.
 * 내가 기록한 가게(bread_record)는 설정에 따라 뺀다(기본) 또는 점수를 깎는다. 코스에 이미 담긴 가게·앞서 보여준 가게는 호출자가
 * excludeStoreIds 로 뺀다.
 * 취향 후보 수(코사인 > 임계)는 창 함수로 풀 전체에서 세어 모든 행에 실린다 — 호출자가 3곳 미만이면 TasteQuery.empty() 로 다시 부른다.
 */
@Repository
@RequiredArgsConstructor
public class RecommendationCandidateJdbcRepository {

	private static final String CENTER = "ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography";

	/** 사용자 축은 자바에서 중심화해 넘기고, 가게 축은 SQL 에서 :c* 를 뺀다(중심화가 꺼져 있으면 0). */
	/** 사용자·가게 4축 코사인 — 후보 점수와 칩의 취향 후보 수가 같은 식을 쓴다. 가게 프로필 alias 는 p, 사용자 축은 :u*, 중심은 :c*. */
	private static final String COSINE = """
			CASE WHEN CAST(:hasAxis AS boolean) AND p.axis_taste IS NOT NULL
					AND p.axis_texture IS NOT NULL AND p.axis_filling IS NOT NULL AND p.axis_vibe IS NOT NULL
				THEN COALESCE(
					(:uTaste * (p.axis_taste - :cTaste) + :uTexture * (p.axis_texture - :cTexture)
						+ :uFilling * (p.axis_filling - :cFilling) + :uVibe * (p.axis_vibe - :cVibe))
					/ NULLIF(sqrt(:uTaste * :uTaste + :uTexture * :uTexture
							+ :uFilling * :uFilling + :uVibe * :uVibe)
						* sqrt(power(p.axis_taste - :cTaste, 2) + power(p.axis_texture - :cTexture, 2)
							+ power(p.axis_filling - :cFilling, 2) + power(p.axis_vibe - :cVibe, 2)), 0), 0)
				ELSE 0 END
			""";

	/** 후보·칩·가게 수가 공유하는 가게 자격 — ACTIVE, 프로필 있음, 빵집다움 하한, (스위치가 켜졌으면) 내가 기록한 가게 제외. 별칭은 s. */
	private static final String ELIGIBLE_STORE = """
			s.status = 'ACTIVE'
			AND EXISTS (SELECT 1 FROM store_profile p
				WHERE p.store_id = s.id AND p.bakery_score >= :minBakeryScore)
			AND NOT (CAST(:excludeRecorded AS boolean) AND EXISTS (SELECT 1 FROM bread_record br
				WHERE br.store_id = s.id AND br.member_id = :memberId AND br.deleted_at IS NULL))
			""";

	private static final String SCORE_PARTS = """
			SELECT parts.cosine, parts.tag_match, parts.prior, parts.kind_overlap, parts.recorded,
				(:wCos * parts.cosine + :wTag * parts.tag_match
					+ :wPrior * parts.prior + :wKind * parts.kind_overlap
					- CASE WHEN parts.recorded THEN :recordedPenalty ELSE 0 END) AS score
			FROM (
				SELECT
					EXISTS (SELECT 1 FROM bread_record br
						WHERE br.store_id = s.id AND br.member_id = :memberId AND br.deleted_at IS NULL) AS recorded,
					%1$s AS cosine,
					COALESCE((SELECT SUM(t.w * COALESCE((p.tags ->> t.k)::float8, 0)) / NULLIF(SUM(t.w), 0)
						FROM unnest(CAST(:tagKeys AS text[]), CAST(:tagWeights AS float8[])) AS t(k, w)), 0)
						AS tag_match,
					(:wReview * LEAST(1, ln(1 + COALESCE(p.review_count, 0)) / ln(1 + CAST(:reviewCap AS float8)))
						+ :wRating * LEAST(1, GREATEST(0,
							((COALESCE(p.rating_count, 0) * COALESCE(p.review_score, :ratingMean)
								+ :ratingShrink * :ratingMean)
								/ (COALESCE(p.rating_count, 0) + :ratingShrink) - :ratingFloor)
							/ (:ratingCeil - :ratingFloor)))
						+ :wPopularity * LEAST(1, s.popularity_score / :popularityCap)) AS prior,
					CASE WHEN cardinality(CAST(:topKinds AS text[])) = 0 OR p.kinds IS NULL THEN 0
						ELSE (SELECT COUNT(*) FROM unnest(p.kinds) AS k
							WHERE k = ANY (CAST(:topKinds AS text[])))::float8
							/ cardinality(CAST(:topKinds AS text[])) END AS kind_overlap
			) parts
			""".formatted(COSINE.strip());

	private static final String SELECT_STORE = """
			SELECT s.id, s.name, s.region_3rd,
				ST_Y(s.location::geometry) AS latitude, ST_X(s.location::geometry) AS longitude,
				ST_Distance(s.location, %1$s) AS distance_m,
				s.popularity_score,
				img.thumbnail_key,
				p.axis_taste, p.axis_texture, p.axis_filling, p.axis_vibe, p.tags::text AS tags, p.kinds,
				sc.cosine, sc.score,
				COUNT(*) FILTER (WHERE sc.cosine > :tasteThreshold) OVER () AS taste_candidate_count
			FROM store s
			JOIN store_profile p ON p.store_id = s.id AND p.bakery_score >= :minBakeryScore
			LEFT JOIN LATERAL (
				SELECT COALESCE(f.resized_key_320, f.aws_key) AS thumbnail_key
				FROM store_image_file f
				WHERE f.store_id = s.id AND (f.resized_key_320 IS NOT NULL OR f.aws_key IS NOT NULL)
				ORDER BY f.display_order ASC, f.id ASC
				LIMIT 1
			) img ON TRUE
			CROSS JOIN LATERAL (%2$s) sc
			WHERE s.status = 'ACTIVE'
				AND s.id <> ALL (:excludeStoreIds)
				AND NOT (CAST(:excludeRecorded AS boolean) AND sc.recorded)
			""".formatted(CENTER, SCORE_PARTS);

	private final NamedParameterJdbcTemplate jdbcTemplate;
	private final RecommendationProperties recommendationProperties;
	private final ObjectMapper objectMapper;

	/**
	 * 상권 후보: 반경 안이거나(주 규칙) 시·도 + 시군구·동이 맞는(보조) 가게를 점수순으로. region2nd 가 null 이면 반경만 본다.
	 * 시·도(store.region_1st = course_area.sido)를 같이 보는 이유: 시군구 이름은 전국에서 겹친다 — 중구·서구·동구·남구가
	 * 시·도마다 있어 "부산 중구"가 대구·대전·인천·울산 중구까지 끌어온다(도장 칸 시드 검증에서 1,446곳).
	 */
	public List<CandidateStoreRow> findAreaCandidates(
			Long memberId, TasteQuery taste, double centerLat, double centerLng, int radiusM, String sido,
			String region2nd, String region3rd, Collection<Long> excludeStoreIds, int limit) {
		StringBuilder sql = new StringBuilder(SELECT_STORE)
			.append("\tAND (ST_DWithin(s.location, ").append(CENTER).append(", :radiusM)");
		MapSqlParameterSource params = centerParams(memberId, taste, centerLat, centerLng, excludeStoreIds)
			.addValue("radiusM", radiusM)
			.addValue("limit", limit);
		if (region2nd != null) {
			sql.append(" OR (s.region_1st = :sido AND s.region_2nd = :region2nd");
			params.addValue("sido", sido);
			params.addValue("region2nd", region2nd);
			if (region3rd != null) {
				sql.append(" AND s.region_3rd = :region3rd");
				params.addValue("region3rd", region3rd);
			}
			sql.append(")");
		}
		sql.append(")\n\tORDER BY sc.score DESC, s.popularity_score DESC, s.id ASC\n\tLIMIT :limit");
		return jdbcTemplate.query(sql.toString(), params, this::mapRow);
	}

	/**
	 * 앵커 후보: 앵커 좌표에서 maxRadiusM 안 가게를 ringM 너비의 링 안쪽부터, 링 안에서는 점수순으로.
	 * 한 번에 상한까지 읽고 호출자가 페이지로 자른다 — 반경 조회 비용은 반경에 거의 비례하지 않는다(운영 실측 1.2km 11ms · 5km 15ms).
	 */
	public List<CandidateStoreRow> findAnchorCandidates(
			Long memberId, TasteQuery taste, double anchorLat, double anchorLng, int ringM, int maxRadiusM,
			Collection<Long> excludeStoreIds, int limit) {
		String sql = SELECT_STORE
			+ "\tAND ST_DWithin(s.location, " + CENTER + ", :maxRadiusM)\n"
			+ "\tORDER BY CEIL(ST_Distance(s.location, " + CENTER
			+ ") / :ringM) ASC, sc.score DESC, s.popularity_score DESC, s.id ASC\n"
			+ "\tLIMIT :limit";
		MapSqlParameterSource params = centerParams(memberId, taste, anchorLat, anchorLng, excludeStoreIds)
			.addValue("maxRadiusM", maxRadiusM)
			.addValue("ringM", ringM)
			.addValue("limit", limit);
		return jdbcTemplate.query(sql, params, this::mapRow);
	}

	/**
	 * 상권 칩 재료를 한 문장으로 — 칩마다 두 번 묻지 않는다(카드 한 번에 6쿼리 → 1쿼리).
	 * 소속 규칙은 후보 조회와 같다(반경 안 OR 시·도+시군구·동 일치, 프로필 있고 빵집다움 하한 이상, 기록한 가게 제외 스위치) —
	 * 칩의 숫자와 실제 후보 수가 어긋나지 않게. 기록한 가게를 빼지 않으면 찜 신호가 그 가게에 남아 이미 기록한 가게만 있는
	 * 동네를 고르고 빈 추천을 낼 수 있다(PR #739 리뷰).
	 *
	 * 칩 순서 신호(#661 §5.2)도 같이 센다: 이 세션에서 상세를 본 가게 수, 내가 저장한 가게 수, 취향 후보(코사인 > 임계) 수.
	 * "이 세션" = 같은 세션 id 의 STORE_VIEW(상한 sessionMaxAgeHours) **또는** 최근 sessionWindowMinutes 창 — 합집합이다.
	 * 세션 id 만 보면 앱 콜드 스타트 분절(초기화 전 UUID → Amplitude 숫자)이나 5분 무활동 뒤 새 세션에서 헤더 없는 앱보다
	 * 신호를 더 잃는다(PR #741 리뷰). 세션·저장은 회원의 작은 가게 집합이라 서브쿼리로 뽑아 조인하고, STORE_VIEW 부분 인덱스를 탄다.
	 *
	 * 시군구 칸(포항시)은 원 1,200m 가 그 시군구 빵집의 30% 안팎만 덮는다(09-15 dev 실측 42칸, 포항 14%). 포항 어디의 가게를
	 * 봤든 "포항에 관심"이므로 대표 행에 한해 같은 시군구의 본·저장 가게 수를 region_* 로 따로 센다 — 칩 순서에만 쓰고,
	 * 추천 후보·칩 숫자·동네 선택은 원 기준 그대로다(코스가 시군구로 벌어지지 않게, PR #735 리뷰). 큰 도시에 원을 더 두는 건 #742.
	 * 칩은 원 기준 합과 시군구 값 중 큰 값을 쓰므로 원이 시군구 경계를 넘을 때(원 안 타 시군구 가게 1 + 원 밖 같은 시군구 가게 1)는
	 * 2 가 아니라 1 로 살짝 덜 센다 — 순서용 신호라 감수한다.
	 */
	public Map<Long, AreaChipStats> findAreaChipStats(Long memberId, String sessionId, TasteQuery taste,
			List<CourseArea> areas) {
		if (areas.isEmpty()) {
			return Map.of();
		}
		String sql = """
				WITH viewed AS (
					SELECT DISTINCT rl.store_id FROM request_log rl
					WHERE rl.member_id = :memberId AND rl.event_type = 'STORE_VIEW' AND rl.store_id IS NOT NULL
						AND ((CAST(:sessionId AS varchar) IS NOT NULL AND rl.session_id = CAST(:sessionId AS varchar)
								AND rl.created_at >= :sessionSince)
							OR rl.created_at >= :since)
				), saved AS (
					SELECT ss.store_id FROM saved_stores ss WHERE ss.member_id = :memberId
				)
				SELECT a.id AS area_id,
					COUNT(s.id) AS store_count,
					COUNT(ss.store_id) AS saved_store_count,
					COUNT(v.store_id) AS session_view_count,
					COUNT(s.id) FILTER (WHERE sc.cosine > :tasteThreshold) AS taste_candidate_count,
					MAX(rv.n) AS region_session_view_count,
					MAX(rs.n) AS region_saved_store_count
				FROM unnest(CAST(:ids AS bigint[]), CAST(:lats AS float8[]), CAST(:lngs AS float8[]),
						CAST(:radii AS int[]), CAST(:sidos AS varchar[]), CAST(:region2nds AS varchar[]),
						CAST(:region3rds AS varchar[]), CAST(:signalRegion2nds AS varchar[]))
					AS a(id, lat, lng, radius_m, sido, region_2nd, region_3rd, signal_region_2nd)
				LEFT JOIN LATERAL (
					SELECT COUNT(*) AS n FROM viewed v2 JOIN store s ON s.id = v2.store_id
					WHERE a.signal_region_2nd IS NOT NULL AND s.region_1st = a.sido
						AND s.region_2nd = a.signal_region_2nd AND %2$s
				) rv ON TRUE
				LEFT JOIN LATERAL (
					SELECT COUNT(*) AS n FROM saved s2 JOIN store s ON s.id = s2.store_id
					WHERE a.signal_region_2nd IS NOT NULL AND s.region_1st = a.sido
						AND s.region_2nd = a.signal_region_2nd AND %2$s
				) rs ON TRUE
				LEFT JOIN store s ON %2$s
					AND (
					ST_DWithin(s.location, ST_SetSRID(ST_MakePoint(a.lng, a.lat), 4326)::geography, a.radius_m)
					OR (a.region_2nd IS NOT NULL AND s.region_1st = a.sido AND s.region_2nd = a.region_2nd
						AND (a.region_3rd IS NULL OR s.region_3rd = a.region_3rd)))
				LEFT JOIN store_profile p ON p.store_id = s.id
				LEFT JOIN LATERAL (SELECT %1$s AS cosine) sc ON TRUE
				LEFT JOIN saved ss ON ss.store_id = s.id
				LEFT JOIN viewed v ON v.store_id = s.id
				GROUP BY a.id
				"""
			.formatted(COSINE.strip(), ELIGIBLE_STORE.strip());
		RecommendationProperties.Score score = recommendationProperties.getScore();
		MapSqlParameterSource params = tasteParams(new MapSqlParameterSource(), taste)
			.addValue("memberId", memberId)
			.addValue("sessionId", sessionId, Types.VARCHAR)
			// request_log.created_at 은 시간대 없는 TIMESTAMP 에 JPA auditing 이 JVM 시각(LocalDateTime.now())을 넣는다. DB now() 와
			// 비교하면 DB 세션 시간대(UTC)로 읽혀 한국 시간 로그가 9시간 미래가 된다(PR #741 리뷰) — 같은 시계로 cutoff 를 만든다.
			.addValue("since", LocalDateTime.now()
				.minusMinutes(recommendationProperties.getArea().getSessionWindowMinutes()))
			.addValue("sessionSince", LocalDateTime.now()
				.minusHours(recommendationProperties.getArea().getSessionMaxAgeHours()))
			.addValue("minBakeryScore", score.getMinBakeryScore())
			.addValue("excludeRecorded", score.isExcludeRecordedStores())
			.addValue("tasteThreshold", score.getTasteCosineThreshold())
			.addValue("ids", areas.stream().map(CourseArea::getId).toArray(Long[]::new))
			.addValue("lats", areas.stream().map(CourseArea::getCenterLat).toArray(Double[]::new))
			.addValue("lngs", areas.stream().map(CourseArea::getCenterLng).toArray(Double[]::new))
			.addValue("radii", areas.stream().map(CourseArea::getRadiusM).toArray(Integer[]::new))
			.addValue("sidos", areas.stream().map(CourseArea::getSido).toArray(String[]::new))
			.addValue("region2nds", areas.stream().map(CourseArea::getRegion2nd).toArray(String[]::new))
			.addValue("region3rds", areas.stream().map(CourseArea::getRegion3rd).toArray(String[]::new))
			.addValue("signalRegion2nds", signalRegion2ndsOf(areas));
		RowMapper<AreaChipStats> mapper = (rs, rowNum) -> new AreaChipStats(
			rs.getLong("area_id"), rs.getInt("store_count"), rs.getInt("saved_store_count"),
			rs.getInt("session_view_count"), rs.getInt("taste_candidate_count"),
			rs.getInt("region_session_view_count"), rs.getInt("region_saved_store_count"));
		return jdbcTemplate.query(sql, params, mapper)
			.stream()
			.collect(Collectors.toMap(AreaChipStats::areaId, stats -> stats));
	}

	/**
	 * 도장 칸(sido + passport_spot)마다 첫 행에만 칸 이름을 준다 — 호출자가 넘기는 목록은 리포지토리가 display_order → name 으로
	 * 정렬한 순서다. 정확성이 아니라 성능 때문이다: ChipSignal 은 region_* 를 합이 아니라 큰 값으로 모으므로 모든 동네 행에 줘도
	 * 결과는 같지만, 시군구 카운트(LATERAL)를 칸당 한 번만 돌리려는 것(PR #743 리뷰). 시군구 이름(포항시 · 해운대구)이면 그 시군구의
	 * 본 · 저장 가게를 세고, 동 이름(성수동 · 도담동)은 store.region_2nd 와 안 맞아 0 이 된다 — 이 문자열 계약은 랩 가드 테스트가 지킨다.
	 */
	private static String[] signalRegion2ndsOf(List<CourseArea> areas) {
		Set<String> seen = new HashSet<>();
		return areas.stream()
			.map(area -> seen.add(area.getSido() + "|" + area.getPassportSpot()) ? area.getPassportSpot() : null)
			.toArray(String[]::new);
	}

	/**
	 * 상권 행 하나의 후보 가게 수만 — 다음 칸 제안(nextArea)의 storeCount 용. 소속·프로필·빵집다움·기록 제외 규칙은 칩 통계와
	 * 같지만 세션·저장·취향 신호는 세지 않는다(로그 서브쿼리·코사인을 돌릴 이유가 없다 — PR #741 리뷰).
	 */
	public int countAreaStores(Long memberId, CourseArea area) {
		String sql = """
				SELECT COUNT(*) FROM store s
				WHERE %1$s
					AND (ST_DWithin(s.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radiusM)
						OR (CAST(:region2nd AS varchar) IS NOT NULL AND s.region_1st = :sido
							AND s.region_2nd = :region2nd
							AND (CAST(:region3rd AS varchar) IS NULL OR s.region_3rd = :region3rd)))
				""".formatted(ELIGIBLE_STORE.strip());
		RecommendationProperties.Score score = recommendationProperties.getScore();
		MapSqlParameterSource params = new MapSqlParameterSource()
			.addValue("memberId", memberId)
			.addValue("minBakeryScore", score.getMinBakeryScore())
			.addValue("excludeRecorded", score.isExcludeRecordedStores())
			.addValue("lat", area.getCenterLat())
			.addValue("lng", area.getCenterLng())
			.addValue("radiusM", area.getRadiusM())
			.addValue("sido", area.getSido())
			.addValue("region2nd", area.getRegion2nd(), Types.VARCHAR)
			.addValue("region3rd", area.getRegion3rd(), Types.VARCHAR);
		Integer count = jdbcTemplate.queryForObject(sql, params, Integer.class);
		return count != null ? count : 0;
	}

	/**
	 * 저장된 추천을 다시 쓰기 전에 그 사이 자격을 잃은 가게를 찾는다(PR #739 리뷰): 그 뒤 내가 기록한 가게(제외 스위치가 켜졌을 때),
	 * ACTIVE 가 아니거나, 프로필·빵집다움 필터를 못 넘는 가게(이 배포 전에 만든 결과). 후보 SQL 과 같은 자격 규칙이다.
	 */
	public Set<Long> findUnavailableStoreIds(Long memberId, Collection<Long> storeIds) {
		if (storeIds.isEmpty()) {
			return Set.of();
		}
		String sql = """
				SELECT s.id FROM store s
				WHERE s.id = ANY (CAST(:ids AS bigint[]))
					AND (s.status <> 'ACTIVE'
						OR NOT EXISTS (SELECT 1 FROM store_profile p
							WHERE p.store_id = s.id AND p.bakery_score >= :minBakeryScore)
						OR (CAST(:excludeRecorded AS boolean) AND EXISTS (SELECT 1 FROM bread_record br
							WHERE br.store_id = s.id AND br.member_id = :memberId AND br.deleted_at IS NULL)))
				""";
		MapSqlParameterSource params = new MapSqlParameterSource()
			.addValue("ids", storeIds.toArray(Long[]::new))
			.addValue("memberId", memberId)
			.addValue("minBakeryScore", recommendationProperties.getScore().getMinBakeryScore())
			.addValue("excludeRecorded", recommendationProperties.getScore().isExcludeRecordedStores());
		return new HashSet<>(jdbcTemplate.queryForList(sql, params, Long.class));
	}

	private MapSqlParameterSource centerParams(Long memberId, TasteQuery taste, double lat, double lng,
			Collection<Long> excludeStoreIds) {
		RecommendationProperties.Score score = recommendationProperties.getScore();
		RecommendationProperties.Prior prior = score.getPrior();
		return tasteParams(new MapSqlParameterSource(), taste)
			.addValue("memberId", memberId)
			.addValue("lat", lat)
			.addValue("lng", lng)
			.addValue("excludeStoreIds", excludeStoreIds.toArray(Long[]::new))
			.addValue("minBakeryScore", score.getMinBakeryScore())
			.addValue("excludeRecorded", score.isExcludeRecordedStores())
			.addValue("recordedPenalty", score.isExcludeRecordedStores() ? 0d : score.getRecordedStorePenalty())
			.addValue("tasteThreshold", score.getTasteCosineThreshold())
			.addValue("wCos", score.getCosineWeight())
			.addValue("wTag", score.getTagWeight())
			.addValue("wPrior", score.getPriorWeight())
			.addValue("wKind", score.getKindWeight())
			.addValue("tagKeys", taste.tags().keySet().toArray(String[]::new))
			.addValue("tagWeights", taste.tags().values().toArray(Double[]::new))
			.addValue("topKinds", taste.topKinds().toArray(String[]::new))
			.addValue("wReview", prior.getReviewWeight())
			.addValue("wRating", prior.getRatingWeight())
			.addValue("wPopularity", prior.getPopularityWeight())
			.addValue("reviewCap", prior.getReviewCap())
			.addValue("ratingShrink", (double)prior.getRatingShrink())
			.addValue("ratingMean", prior.getRatingMean())
			.addValue("ratingFloor", prior.getRatingFloor())
			.addValue("ratingCeil", prior.getRatingCeil())
			.addValue("popularityCap", prior.getPopularityCap());
	}

	/** 코사인식(COSINE)이 쓰는 파라미터 — 사용자 축은 여기서 중심화하고, 가게 축은 SQL 이 :c* 를 뺀다(중심화가 꺼져 있으면 0). */
	private MapSqlParameterSource tasteParams(MapSqlParameterSource params, TasteQuery taste) {
		RecommendationProperties.Score score = recommendationProperties.getScore();
		boolean center = score.isCenterAxes();
		double cTaste = center ? score.getCenterTaste() : 0;
		double cTexture = center ? score.getCenterTexture() : 0;
		double cFilling = center ? score.getCenterFilling() : 0;
		double cVibe = center ? score.getCenterVibe() : 0;
		boolean hasAxis = taste.hasAxis();
		return params
			.addValue("hasAxis", hasAxis)
			.addValue("uTaste", hasAxis ? taste.taste() - cTaste : 0d)
			.addValue("uTexture", hasAxis ? taste.texture() - cTexture : 0d)
			.addValue("uFilling", hasAxis ? taste.filling() - cFilling : 0d)
			.addValue("uVibe", hasAxis ? taste.vibe() - cVibe : 0d)
			.addValue("cTaste", cTaste)
			.addValue("cTexture", cTexture)
			.addValue("cFilling", cFilling)
			.addValue("cVibe", cVibe);
	}

	private CandidateStoreRow mapRow(ResultSet rs, int rowNum) throws SQLException {
		return new CandidateStoreRow(
			rs.getLong("id"),
			rs.getString("name"),
			rs.getString("region_3rd"),
			rs.getDouble("latitude"),
			rs.getDouble("longitude"),
			nullableString(rs, "thumbnail_key"),
			rs.getDouble("distance_m"),
			rs.getDouble("popularity_score"),
			rs.getDouble("score"),
			rs.getDouble("cosine"),
			rs.getInt("taste_candidate_count"),
			nullableDouble(rs, "axis_taste"),
			nullableDouble(rs, "axis_texture"),
			nullableDouble(rs, "axis_filling"),
			nullableDouble(rs, "axis_vibe"),
			readTags(rs.getString("tags")),
			readKinds(rs.getArray("kinds")));
	}

	private static String nullableString(ResultSet rs, String column) throws SQLException {
		String value = rs.getString(column);
		return rs.wasNull() ? null : value;
	}

	private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
		double value = rs.getDouble(column);
		return rs.wasNull() ? null : value;
	}

	private Map<String, Double> readTags(String json) {
		if (json == null || json.isBlank()) {
			return Map.of();
		}
		try {
			return objectMapper.readValue(json, objectMapper.getTypeFactory()
				.constructMapType(Map.class, String.class, Double.class));
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("store_profile.tags 를 읽을 수 없습니다: " + json, e);
		}
	}

	private static List<String> readKinds(Array array) throws SQLException {
		if (array == null) {
			return List.of();
		}
		return Arrays.asList((String[])array.getArray());
	}
}

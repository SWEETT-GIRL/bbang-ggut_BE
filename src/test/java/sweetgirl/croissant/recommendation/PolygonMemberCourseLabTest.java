package sweetgirl.croissant.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static sweetgirl.croissant.recommendation.PolygonCourseComparisonLabTest.approach;
import static sweetgirl.croissant.recommendation.PolygonCourseComparisonLabTest.bestCourse;
import static sweetgirl.croissant.recommendation.PolygonCourseComparisonLabTest.better;
import static sweetgirl.croissant.recommendation.PolygonCourseComparisonLabTest.distance;
import static sweetgirl.croissant.recommendation.PolygonCourseComparisonLabTest.distances;
import static sweetgirl.croissant.recommendation.PolygonCourseComparisonLabTest.fallback;
import static sweetgirl.croissant.recommendation.PolygonCourseComparisonLabTest.ids;
import static sweetgirl.croissant.recommendation.PolygonCourseComparisonLabTest.measure;
import static sweetgirl.croissant.recommendation.PolygonCourseComparisonLabTest.nearestIndexes;
import static sweetgirl.croissant.recommendation.PolygonCourseComparisonLabTest.repository;
import static sweetgirl.croissant.recommendation.PolygonCourseComparisonLabTest.top15;
import static sweetgirl.croissant.recommendation.PolygonCourseComparisonLabTest.totalDistance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.personalization.entity.TasteAxis;
import sweetgirl.croissant.personalization.entity.UserTasteProfile;
import sweetgirl.croissant.recommendation.config.RecommendationProperties;
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;
import sweetgirl.croissant.recommendation.repository.RecommendationCandidateJdbcRepository;
import sweetgirl.croissant.recommendation.repository.TasteQuery;
import sweetgirl.croissant.recommendation.service.CourseRecommendationCommandService;

/** 실제 로컬 프로필·방문 제외 + 담기/재추천 모의 상황. 원본 회원 정보와 경로는 Git 밖에만 저장한다. */
@Tag("lab")
class PolygonMemberCourseLabTest {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final int HOP = 1_500;

	record MemberInput(long memberId, String stratum, TasteQuery taste) {
	}

	@Test
	void compareActualProfilesAndSimulatedContexts() throws Exception {
		assertThat(System.getenv("POLYGON_COURSE_INPUT")).isNotBlank();
		assertThat(System.getenv("EVAL_OUT")).isNotBlank();
		JsonNode input = JSON.readTree(Files.readString(Path.of(System.getenv("POLYGON_COURSE_INPUT"))));
		List<Map<String, Object>> rows = new ArrayList<>();
		List<MemberInput> members;
		RecommendationProperties config = new RecommendationProperties();
		try (Connection connection = DriverManager.getConnection(
			"jdbc:postgresql://127.0.0.1:5432/soboro", "sweetgirl", "soboro-lab")) {
			connection.setReadOnly(true);
			connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
			connection.setAutoCommit(false);
			NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(
				new SingleConnectionDataSource(connection, true));
			assertThat(jdbc.queryForObject("SHOW transaction_read_only", Map.of(), String.class)).isEqualTo("on");
			assertThat(
				jdbc.queryForObject("SELECT system_identifier::text FROM pg_control_system()", Map.of(), String.class))
				.isEqualTo(input.path("databaseSystemId").asText());
			assertThat(jdbc.queryForObject("SELECT max(updated_at)::text FROM store", Map.of(), String.class))
				.isEqualTo(input.path("storeLatestUpdate").asText());
			List<Long> pilotIds = new ArrayList<>();
			for (JsonNode area : input.path("areas")) {
				input.path("scopes").path(area.path("passport_spot").asText()).forEach(id -> pilotIds.add(id.asLong()));
			}
			members = jdbc.query(
				"""
						WITH cohort AS (
							SELECT p.*, CASE WHEN p.axis_taste IS NULL THEN 'NO_AXIS'
							WHEN cardinality(coalesce(p.top_kinds,'{}'))>0 THEN 'AXIS_KINDS'
							ELSE 'AXIS_ONLY' END stratum,
							(SELECT count(*) FROM bread_record br WHERE br.member_id=p.member_id
							AND br.deleted_at IS NULL
							AND br.store_id=ANY(CAST(:pilot AS bigint[]))) local_records
							FROM user_taste_profile p
						), ranked AS (
							SELECT *,row_number() OVER(PARTITION BY stratum
								ORDER BY local_records DESC,member_id) position FROM cohort
						) SELECT * FROM ranked WHERE position<=6 ORDER BY stratum,position
						""",
				Map.of("pilot", pilotIds.toArray(Long[]::new)), PolygonMemberCourseLabTest::member);
			assertThat(members).hasSize(18);
			RecommendationCandidateJdbcRepository baseline = repository(jdbc, config);
			for (JsonNode area : input.path("areas")) {
				String city = area.path("passport_spot").asText();
				List<Long> scopeIds = new ArrayList<>();
				input.path("scopes").path(city).forEach(id -> scopeIds.add(id.asLong()));
				RecommendationCandidateJdbcRepository polygon = repository(
					new PolygonCourseComparisonLabTest.ScopeJdbc(jdbc, scopeIds), config);
				List<CandidateStoreRow> cityAll = fetch(polygon, -1, TasteQuery.empty(), area, true);
				assertThat(ids(cityAll)).containsExactlyInAnyOrderElementsOf(scopeIds);
				Set<Long> originalIds = new HashSet<>(ids(fetch(baseline, -1, TasteQuery.empty(), area, false)));
				Map<Long, Integer> index = new HashMap<>();
				for (int i = 0; i < cityAll.size(); i++) {
					index.put(cityAll.get(i).storeId(), i);
				}
				int[][] matrix = distances(cityAll);
				int[][] neighbors = nearestIndexes(matrix);
				for (int memberIndex = 0; memberIndex < members.size(); memberIndex++) {
					MemberInput member = members.get(memberIndex);
					Set<Long> recorded = new HashSet<>(jdbc.queryForList(
						"SELECT DISTINCT store_id FROM bread_record WHERE member_id=:member AND deleted_at IS NULL",
						Map.of("member", member.memberId()), Long.class));
					List<CandidateStoreRow> cityScored = fetch(polygon, member.memberId(), member.taste(), area, true);
					List<CandidateStoreRow> areaScored = fetch(baseline, member.memberId(), member.taste(), area,
						false);
					List<CandidateStoreRow> cityNeutral = fetch(polygon, member.memberId(), TasteQuery.empty(), area,
						true);
					List<CandidateStoreRow> areaNeutral = fetch(baseline, member.memberId(), TasteQuery.empty(), area,
						false);
					assertThat(ids(cityScored)).noneMatch(recorded::contains);
					Map<Long, CandidateStoreRow> common = new HashMap<>();
					cityScored.forEach(row -> common.put(row.storeId(), row));
					areaScored.forEach(row -> common.put(row.storeId(), row));
					assertThat(areaScored).isNotEmpty();
					CandidateStoreRow anchor = areaScored.get(0);
					for (int count : List.of(4, 6)) {
						Set<Long> shown = new HashSet<>();
						CandidateStoreRow retryCenter = anchor;
						for (String context : List.of("FRESH", "ADDED_STORE", "RETRY_FROZEN")) {
							boolean retry = context.equals("RETRY_FROZEN");
							CandidateStoreRow fixed = context.equals("ADDED_STORE") ? anchor : null;
							Set<Long> excluded = retry ? new HashSet<>(shown) : new HashSet<>();
							if (fixed != null) {
								excluded.add(fixed.storeId());
							}
							// RETRY는 직전 HYBRID 첫 가게의 1,200m 범위로 고정하는 제안 계약을 실험한다.
							List<CandidateStoreRow> reference = filter(retry ? cityScored : areaScored, excluded,
								retry ? retryCenter : null);
							List<CandidateStoreRow> cityPool = filter(cityScored, excluded, retry ? retryCenter : null);
							boolean referenceFallback = fallback(reference, member.taste());
							boolean cityFallback = fallback(cityPool, member.taste());
							if (referenceFallback) {
								reference = filter(retry ? cityNeutral : areaNeutral, excluded,
									retry ? retryCenter : null);
							}
							if (cityFallback) {
								cityPool = filter(cityNeutral, excluded, retry ? retryCenter : null);
							}
							int budget = 500 * (count - 1);
							List<CandidateStoreRow> control = CourseRecommendationCommandService
								.chainWithinHop(top15(reference), HOP, count);
							long start = System.nanoTime();
							List<CandidateStoreRow> tight = bestCourse(cityPool, count, common, index, matrix, budget,
								fixed, false);
							double tightMs = (System.nanoTime() - start) / 1e6;
							start = System.nanoTime();
							List<CandidateStoreRow> hybrid = bestCourse(cityPool, count, common, index, matrix, budget,
								fixed, true, neighbors);
							if (better(tight, hybrid, common, fixed)) {
								hybrid = tight;
							}
							Set<Long> allowed = new HashSet<>(ids(cityPool));
							boolean controlFits = allowed.containsAll(ids(control)) && approach(control, fixed) <= HOP
								&& totalDistance(control) + approach(control, fixed) <= budget;
							if (controlFits && better(control, hybrid, common, fixed)) {
								hybrid = control;
							}
							double hybridMs = tightMs + (System.nanoTime() - start) / 1e6;
							assertThat(better(tight, hybrid, common, fixed)).isFalse();
							if (controlFits) {
								assertThat(better(control, hybrid, common, fixed)).isFalse();
							}
							Map<String, List<CandidateStoreRow>> routes = new LinkedHashMap<>();
							routes.put("CONTROL", control);
							routes.put("D_TIGHT", tight);
							routes.put("D_HYBRID", hybrid);
							for (Map.Entry<String, List<CandidateStoreRow>> entry : routes.entrySet()) {
								List<CandidateStoreRow> route = entry.getValue();
								assertThat(ids(route)).noneMatch(recorded::contains)
									.noneMatch(excluded::contains);
								if (!entry.getKey().equals("CONTROL")) {
									assertThat(allowed).containsAll(ids(route));
									assertThat(totalDistance(route) + approach(route, fixed))
										.isLessThanOrEqualTo(budget);
									assertThat(approach(route, fixed)).isLessThanOrEqualTo(HOP);
								}
								Map<String, Object> result = measure(entry.getKey(), city, "PROFILE_" + memberIndex,
									false, count,
									route, entry.getKey().equals("CONTROL") ? referenceFallback : cityFallback, common,
									originalIds, System.nanoTime());
								result.put("context", context);
								result.put("stratum", member.stratum());
								result.put("hasAxis", member.taste().hasAxis());
								result.put("hasKinds", !member.taste().topKinds().isEmpty());
								result.put("approachM", approach(route, fixed));
								result.put("distanceWithApproachM", totalDistance(route) + approach(route, fixed));
								result.put("controlFits", controlFits);
								result.put("recordedInCity", scopeIds.stream().filter(recorded::contains).count());
								if (entry.getKey().equals("CONTROL")) {
									result.put("algorithmMs", null);
								} else {
									result.put("algorithmMs", entry.getKey().equals("D_TIGHT") ? tightMs : hybridMs);
								}
								rows.add(result);
							}
							if (context.equals("FRESH")) {
								shown.addAll(ids(control));
								shown.addAll(ids(tight));
								shown.addAll(ids(hybrid));
								retryCenter = hybrid.get(0);
							}
						}
					}
				}
			}
			connection.rollback();
		}
		Map<String, Object> output = new LinkedHashMap<>();
		output.put("input", input);
		output.put("settings", JSON.valueToTree(config));
		output.put("members", members);
		output.put("rows", rows);
		output.put("limitations", List.of(
			"로컬 프로필 18개, 층별 6개. 시범 지역 방문 기록이 많은 순서이며 대표 표본은 아니다.",
			"취향·방문 제외는 실제 로컬 데이터다. 담은 가게와 재추천은 실제 요청이 아닌 모의 상황이다.",
			"RETRY_FROZEN은 첫 HYBRID 가게의 1,200m 원에 고정한다. CONTROL도 같은 범위에 기존 알고리즘을 적용한다.",
			"재추천 제외는 첫 회차 모든 비교군의 합집합이다. 운영 API 재현이 아니며 범위 계약은 제안이다.",
			"직선거리이며 담은 가게부터 첫 추천까지의 거리도 예산에 포함한다. API·DB 쓰기는 없다.",
			"계산 시간은 DB·거리 행렬·이웃 정렬을 제외하며 HYBRID의 두 탐색은 모두 포함한다. 운영 지연이 아니다."));
		Path out = Path.of(System.getenv("EVAL_OUT"));
		Files.createDirectories(out);
		Files.writeString(out.resolve("member-comparison.json"),
			JSON.writerWithDefaultPrettyPrinter().writeValueAsString(output));
		System.out.println("Member comparison cases=" + rows.size() + "; output=" + out);
	}

	private static List<CandidateStoreRow> filter(List<CandidateStoreRow> rows, Set<Long> excluded,
			CandidateStoreRow center) {
		return rows.stream().filter(row -> !excluded.contains(row.storeId()))
			.filter(row -> center == null || distance(center, row) <= 1_200).toList();
	}

	private static List<CandidateStoreRow> fetch(RecommendationCandidateJdbcRepository repository, long memberId,
			TasteQuery taste, JsonNode area, boolean city) {
		List<CandidateStoreRow> rows = repository.findAreaCandidates(memberId, taste,
			area.path("center_lat").asDouble(), area.path("center_lng").asDouble(),
			city ? 20_000_000 : area.path("radius_m").asInt(),
			area.path("sido").asText(),
			city || area.path("region_2nd").isNull() ? null : area.path("region_2nd").asText(),
			city || area.path("region_3rd").isNull() ? null : area.path("region_3rd").asText(), List.of(), 100_000);
		assertThat(rows.size()).isLessThan(100_000);
		return rows;
	}

	static MemberInput member(ResultSet row, int rowNumber) throws SQLException {
		try {
			LocalDateTime computedAt = row.getTimestamp("computed_at").toLocalDateTime();
			UserTasteProfile profile = UserTasteProfile.createEmpty(row.getLong("member_id"), computedAt);
			profile.recompute(TasteAxis.of(row.getBigDecimal("axis_taste"), row.getBigDecimal("axis_texture"),
				row.getBigDecimal("axis_filling"), row.getBigDecimal("axis_vibe")), null, null, null,
				JSON.readValue(row.getString("tags"),
					JSON.getTypeFactory().constructMapType(Map.class, String.class, Double.class)),
				row.getArray("top_kinds") == null ? null : (String[])row.getArray("top_kinds").getArray(),
				row.getArray("inferred_kinds") == null ? null : (String[])row.getArray("inferred_kinds").getArray(),
				null, row.getInt("strong_store_count"), row.getInt("bread_signal_count"), computedAt);
			return new MemberInput(profile.getMemberId(), row.getString("stratum"), TasteQuery.of(profile));
		} catch (java.io.IOException exception) {
			throw new SQLException("Invalid profile tag JSON", exception);
		}
	}
}

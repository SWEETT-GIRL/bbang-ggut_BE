package sweetgirl.croissant.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.common.util.GeoDistance;
import sweetgirl.croissant.recommendation.config.RecommendationProperties;
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;
import sweetgirl.croissant.recommendation.repository.RecommendationCandidateJdbcRepository;
import sweetgirl.croissant.recommendation.repository.StoreLabelStatsRepository;
import sweetgirl.croissant.recommendation.repository.TasteQuery;
import sweetgirl.croissant.recommendation.service.CourseRecommendationCommandService;
import sweetgirl.croissant.recommendation.service.LabelCenterParams;

/**
 * Local, read-only A / city top-15 / D experiment. No Spring context, migrations, members or LLM calls.
 * Uses the actual candidate score SQL and actual baseline chain function. The scope adapter below
 * adds only an ID membership predicate; it is not a production repository implementation.
 * POLYGON_COURSE_INPUT is produced by scripts/lab/course_polygon_input.py; EVAL_OUT stays outside Git.
 */
@Tag("lab")
class PolygonCourseComparisonLabTest {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final int FULL_LIMIT = 100_000;
	private static final int HOP = 1_500;
	private static final String ORDER = "\n\tORDER BY sc.score DESC";

	@Test
	void compareLocalCityCourses() throws Exception {
		String inputPath = System.getenv("POLYGON_COURSE_INPUT");
		String outputPath = System.getenv("EVAL_OUT");
		assertThat(inputPath).as("Set POLYGON_COURSE_INPUT to the local membership export").isNotBlank();
		assertThat(outputPath).as("Set EVAL_OUT to a directory outside Git").isNotBlank();
		JsonNode input = JSON.readTree(Files.readString(Path.of(inputPath)));
		List<Map<String, Object>> results = new ArrayList<>();
		List<Map<String, Object>> scopeStats = new ArrayList<>();
		List<Map<String, Object>> queryTimes = new ArrayList<>();
		RecommendationProperties config = new RecommendationProperties();
		// Fixed lab endpoint; verify the Docker database identity exported via its Unix socket.
		try (Connection connection = DriverManager.getConnection(
			"jdbc:postgresql://127.0.0.1:5432/soboro", "sweetgirl", "soboro-lab")) {
			connection.setReadOnly(true);
			connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
			connection.setAutoCommit(false);
			NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(
				new SingleConnectionDataSource(connection, true));
			assertThat(jdbc.queryForObject("SHOW transaction_read_only", Map.of(), String.class)).isEqualTo("on");
			assertThat(jdbc.queryForObject("SELECT system_identifier::text FROM pg_control_system()", Map.of(),
				String.class)).isEqualTo(input.path("databaseSystemId").asText());
			assertThat(jdbc.queryForObject("SELECT max(updated_at)::text FROM store", Map.of(), String.class))
				.isEqualTo(input.path("storeLatestUpdate").asText());
			assertThat(jdbc.queryForObject("SELECT count(*) FROM store", Map.of(), Long.class))
				.isEqualTo(input.path("storeCount").asLong());
			assertThat(jdbc.queryForObject("SELECT max(computed_at)::text FROM store_profile", Map.of(), String.class))
				.isEqualTo(input.path("profileLatestUpdate").asText());
			RecommendationCandidateJdbcRepository baseline = repository(jdbc, config);
			for (JsonNode area : input.path("areas")) {
				String city = area.path("passport_spot").asText();
				List<Long> scopeIds = new ArrayList<>();
				input.path("scopes").path(city).forEach(id -> scopeIds.add(id.asLong()));
				assertThat(scopeIds).isNotEmpty();
				assertThat(area.path("max_hop_m").asInt()).isEqualTo(HOP);
				RecommendationCandidateJdbcRepository polygon = repository(new ScopeJdbc(jdbc, scopeIds), config);
				List<CandidateStoreRow> cityNeutral = fetch(polygon, TasteQuery.empty(), area, true, queryTimes);
				assertThat(ids(cityNeutral)).containsExactlyInAnyOrderElementsOf(scopeIds);
				List<CandidateStoreRow> areaNeutral = fetch(baseline, TasteQuery.empty(), area, false, queryTimes);
				Set<Long> areaIds = new HashSet<>(ids(areaNeutral));
				Map<Long, Integer> index = new HashMap<>();
				for (int i = 0; i < cityNeutral.size(); i++) {
					index.put(cityNeutral.get(i).storeId(), i);
				}
				int[][] distances = distances(cityNeutral);
				scopeStats.add(Map.of("city", city, "polygonCandidates", scopeIds.size(),
					"currentAreaCandidates", areaIds.size(), "currentAreaInCity",
					scopeIds.stream().filter(areaIds::contains).count(),
					"areaId", area.path("id").asLong()));
				for (Map.Entry<String, TasteQuery> persona : personas().entrySet()) {
					List<CandidateStoreRow> areaScored = fetch(baseline, persona.getValue(), area, false, queryTimes);
					List<CandidateStoreRow> cityScored = fetch(polygon, persona.getValue(), area, true, queryTimes);
					Map<Long, CandidateStoreRow> common = new HashMap<>();
					cityScored.forEach(row -> common.put(row.storeId(), row));
					areaScored.forEach(row -> {
						if (common.containsKey(row.storeId())) {
							assertThat(row.score()).isEqualTo(common.get(row.storeId()).score());
						}
						common.put(row.storeId(), row);
					});
					for (boolean exclude : List.of(false, true)) {
						List<CandidateStoreRow> areaPool = eligible(areaScored, exclude);
						List<CandidateStoreRow> cityPool = eligible(cityScored, exclude);
						boolean aFallback = fallback(areaPool, persona.getValue());
						boolean dFallback = fallback(cityPool, persona.getValue());
						if (aFallback) {
							areaPool = eligible(areaNeutral, exclude);
						}
						if (dFallback) {
							cityPool = eligible(cityNeutral, exclude);
						}
						for (int count : List.of(1, 4, 6)) {
							long start = System.nanoTime();
							List<CandidateStoreRow> aRoute = CourseRecommendationCommandService.chainWithinHop(
								top15(areaPool),
								HOP, count);
							results.add(measure("A", city, persona.getKey(), exclude, count, aRoute, aFallback, common,
								areaIds, start));
							start = System.nanoTime();
							List<CandidateStoreRow> naive = CourseRecommendationCommandService.chainWithinHop(
								top15(cityPool),
								HOP, count);
							results.add(measure("CITY_TOP15", city, persona.getKey(), exclude, count, naive, dFallback,
								common, areaIds, start));
							start = System.nanoTime();
							List<CandidateStoreRow> route = bestCourse(cityPool, count, common, index, distances,
								Integer.MAX_VALUE);
							results.add(measure("D", city, persona.getKey(), exclude, count, route, dFallback, common,
								areaIds, start));
							start = System.nanoTime();
							int budget = 500 * (count - 1);
							List<CandidateStoreRow> tight = bestCourse(cityPool, count, common, index, distances,
								budget);
							assertThat(totalDistance(tight)).isLessThanOrEqualTo(budget);
							assertThat(ids(tight)).allSatisfy(id -> {
								assertThat(scopeIds).contains(id);
								assertThat(exclude && id % 5 == 0).isFalse();
							});
							results.add(measure("D_TIGHT", city, persona.getKey(), exclude, count, tight, dFallback,
								common, areaIds, start));
							assertThat(route).allSatisfy(row -> {
								assertThat(scopeIds).contains(row.storeId());
								assertThat(exclude && row.storeId() % 5 == 0).isFalse();
							});
						}
					}
				}
			}
			connection.rollback();
		}
		Path out = Path.of(outputPath);
		Files.createDirectories(out);
		Map<String, Object> report = new LinkedHashMap<>();
		report.put("input", input);
		report.put("settings", JSON.valueToTree(config));
		report.put("personas", personas());
		report.put("scopeStats", scopeStats);
		report.put("queryTimes", queryTimes);
		report.put("rows", results);
		report.put("limitations", List.of(
			"Synthetic axis personas, not observed member satisfaction; AXIS defaults, no runtime overrides.",
			"Empty course, no real histories/session/saves; shared exclusion is deterministic storeId % 5 == 0.",
			"D is all-seed greedy by score, not an optimal path search; no beam search or road routing.",
			"Algorithm timings exclude SQL and precomputed distance matrix. Not API latency.",
			"No dynamic scope persistence or retry API contract implemented. Sejong chips remain dong/eup scoped."));
		Files.writeString(out.resolve("comparison.json"),
			JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
		System.out.println("Polygon comparison cases=" + results.size() + "; output=" + out);
	}

	static RecommendationCandidateJdbcRepository repository(NamedParameterJdbcTemplate jdbc,
			RecommendationProperties config) {
		return new RecommendationCandidateJdbcRepository(jdbc, config,
			new LabelCenterParams(new StoreLabelStatsRepository(jdbc)), JSON);
	}

	/** Test-only adapter: use production scoring, inject only polygon membership before sort/LIMIT. */
	static class ScopeJdbc extends NamedParameterJdbcTemplate {
		private final List<Long> ids;

		ScopeJdbc(NamedParameterJdbcTemplate jdbc, List<Long> ids) {
			super(jdbc.getJdbcTemplate());
			this.ids = ids;
		}

		@Override
		public <T> List<T> query(String sql, SqlParameterSource params, RowMapper<T> mapper) {
			assertThat(sql).contains(ORDER);
			MapSqlParameterSource scoped = new MapSqlParameterSource();
			for (String name : params.getParameterNames()) {
				scoped.addValue(name, params.getValue(name));
			}
			scoped.addValue("labScopeIds", ids.toArray(Long[]::new));
			return super.query(sql.replace(ORDER, "\n AND s.id = ANY(CAST(:labScopeIds AS bigint[]))" + ORDER), scoped,
				mapper);
		}
	}

	static List<CandidateStoreRow> fetch(RecommendationCandidateJdbcRepository repository, TasteQuery taste,
			JsonNode area, boolean city, List<Map<String, Object>> times) {
		long start = System.nanoTime();
		List<CandidateStoreRow> rows = repository.findAreaCandidates(-1L, taste,
			area.path("center_lat").asDouble(), area.path("center_lng").asDouble(),
			city ? 20_000_000 : area.path("radius_m").asInt(), area.path("sido").asText(),
			city || area.path("region_2nd").isNull() ? null : area.path("region_2nd").asText(),
			city || area.path("region_3rd").isNull() ? null : area.path("region_3rd").asText(), List.of(), FULL_LIMIT);
		assertThat(rows.size()).isLessThan(FULL_LIMIT);
		times.add(Map.of("city", area.path("passport_spot").asText(), "scope", city ? "POLYGON" : "AREA",
			"ms", (System.nanoTime() - start) / 1e6, "candidates", rows.size()));
		return rows;
	}

	private static Map<String, TasteQuery> personas() {
		Map<String, TasteQuery> result = new LinkedHashMap<>();
		result.put("NO_TASTE", TasteQuery.empty());
		for (int mask = 0; mask < 16; mask++) {
			result.put("AXIS_" + mask, new TasteQuery((mask & 1) == 0 ? -.8 : .8,
				(mask & 2) == 0 ? -.8 : .8, (mask & 4) == 0 ? -.8 : .8, (mask & 8) == 0 ? -.8 : .8,
				Map.of(), List.of(), 0, List.of()));
		}
		return result;
	}

	static boolean fallback(List<CandidateStoreRow> rows, TasteQuery taste) {
		return taste.hasAxis() && !rows.isEmpty() && rows.stream().filter(row -> row.tasteFit() > .2).count() < 3;
	}

	private static List<CandidateStoreRow> eligible(List<CandidateStoreRow> rows, boolean exclude) {
		return rows.stream().filter(row -> !exclude || row.storeId() % 5 != 0).toList();
	}

	static List<CandidateStoreRow> top15(List<CandidateStoreRow> rows) {
		return rows.subList(0, Math.min(15, rows.size()));
	}

	static List<Long> ids(List<CandidateStoreRow> rows) {
		return rows.stream().map(CandidateStoreRow::storeId).toList();
	}

	static int distance(CandidateStoreRow first, CandidateStoreRow second) {
		return GeoDistance.metersBetween(first.latitude(), first.longitude(), second.latitude(), second.longitude());
	}

	static int[][] distances(List<CandidateStoreRow> rows) {
		int[][] distances = new int[rows.size()][rows.size()];
		for (int i = 0; i < rows.size(); i++) {
			for (int j = 0; j < i; j++) {
				distances[i][j] = distances[j][i] = distance(rows.get(i), rows.get(j));
			}
		}
		return distances;
	}

	static List<CandidateStoreRow> bestCourse(List<CandidateStoreRow> ranked, int count,
			Map<Long, CandidateStoreRow> common, Map<Long, Integer> index, int[][] distances, int totalBudget) {
		return bestCourse(ranked, count, common, index, distances, totalBudget, null, false);
	}

	/** 고정 장소가 있으면 첫 연결 거리도 예산에 포함한다. 가까운 순서와 점수 순서를 별도로 탐색한다. */
	static List<CandidateStoreRow> bestCourse(List<CandidateStoreRow> ranked, int count,
			Map<Long, CandidateStoreRow> common, Map<Long, Integer> index, int[][] distances, int totalBudget,
			CandidateStoreRow anchor, boolean nearest) {
		return bestCourse(ranked, count, common, index, distances, totalBudget, anchor, nearest,
			nearest ? nearestIndexes(distances) : null);
	}

	/** 좌표 스냅샷마다 한 번 준비한다. 동거리 후보는 요청의 점수 순서를 이용해 결정한다. */
	static int[][] nearestIndexes(int[][] distances) {
		int[][] result = new int[distances.length][];
		for (int index = 0; index < distances.length; index++) {
			int[] row = distances[index];
			result[index] = IntStream.range(0, row.length).filter(other -> row[other] <= HOP).boxed()
				.sorted(Comparator.comparingInt(other -> row[other])).mapToInt(Integer::intValue).toArray();
		}
		return result;
	}

	static List<CandidateStoreRow> bestCourse(List<CandidateStoreRow> ranked, int count,
			Map<Long, CandidateStoreRow> common, Map<Long, Integer> index, int[][] distances, int totalBudget,
			CandidateStoreRow anchor, boolean nearest, int[][] neighbors) {
		List<CandidateStoreRow> best = List.of();
		CandidateStoreRow[] available = new CandidateStoreRow[index.size()];
		int[] rankOrder = new int[index.size()];
		for (int rank = 0; rank < ranked.size(); rank++) {
			int position = index.get(ranked.get(rank).storeId());
			available[position] = ranked.get(rank);
			rankOrder[position] = rank;
		}
		for (CandidateStoreRow seed : ranked) {
			int approach = anchor == null ? 0 : distance(anchor, seed);
			if (approach > HOP || approach > totalBudget) {
				continue;
			}
			List<CandidateStoreRow> route = new ArrayList<>(List.of(seed));
			int traveled = approach;
			while (route.size() < count) {
				CandidateStoreRow last = route.get(route.size() - 1);
				CandidateStoreRow next = null;
				int nextHop = Integer.MAX_VALUE;
				int lastIndex = index.get(last.storeId());
				if (nearest) {
					for (int neighbor : neighbors[lastIndex]) {
						int hop = distances[lastIndex][neighbor];
						if (hop > nextHop) {
							break;
						}
						CandidateStoreRow candidate = available[neighbor];
						if (candidate != null && !route.contains(candidate) && traveled + hop <= totalBudget
							&& (next == null || rankOrder[neighbor] < rankOrder[index.get(next.storeId())])) {
							next = candidate;
							nextHop = hop;
						}
					}
				} else {
					for (CandidateStoreRow candidate : ranked) {
						int hop = distances[lastIndex][index.get(candidate.storeId())];
						if (!route.contains(candidate) && hop <= HOP && traveled + hop <= totalBudget) {
							next = candidate;
							nextHop = hop;
							break;
						}
					}
				}
				if (next == null) {
					break;
				}
				traveled += nextHop;
				route.add(next);
			}
			if (better(route, best, common, anchor)) {
				best = route;
			}
		}
		return best;
	}

	static boolean better(List<CandidateStoreRow> first, List<CandidateStoreRow> second,
			Map<Long, CandidateStoreRow> common) {
		return better(first, second, common, null);
	}

	static boolean better(List<CandidateStoreRow> first, List<CandidateStoreRow> second,
			Map<Long, CandidateStoreRow> common, CandidateStoreRow anchor) {
		if (first.size() != second.size()) {
			return first.size() > second.size();
		}
		double scoreA = first.stream().mapToDouble(row -> common.get(row.storeId()).score()).sum();
		double scoreB = second.stream().mapToDouble(row -> common.get(row.storeId()).score()).sum();
		if (Double.compare(scoreA, scoreB) != 0) {
			return scoreA > scoreB;
		}
		return totalDistance(first) + approach(first, anchor) < totalDistance(second) + approach(second, anchor);
	}

	static int approach(List<CandidateStoreRow> route, CandidateStoreRow anchor) {
		return anchor == null || route.isEmpty() ? 0 : distance(anchor, route.get(0));
	}

	static int totalDistance(List<CandidateStoreRow> route) {
		int total = 0;
		for (int i = 1; i < route.size(); i++) {
			total += distance(route.get(i - 1), route.get(i));
		}
		return total;
	}

	static Map<String, Object> measure(String arm, String city, String persona, boolean exclude, int count,
			List<CandidateStoreRow> route, boolean fallback, Map<Long, CandidateStoreRow> common, Set<Long> oldIds,
			long start) {
		double elapsed = (System.nanoTime() - start) / 1e6;
		assertThat(new HashSet<>(ids(route))).hasSize(route.size());
		int maxHop = 0;
		int diameter = 0;
		for (int i = 0; i < route.size(); i++) {
			if (i > 0) {
				maxHop = Math.max(maxHop, distance(route.get(i - 1), route.get(i)));
			}
			for (int j = 0; j < i; j++) {
				diameter = Math.max(diameter, distance(route.get(j), route.get(i)));
			}
		}
		assertThat(maxHop).isLessThanOrEqualTo(HOP);
		assertThat(route.size()).isLessThanOrEqualTo(count);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("arm", arm);
		result.put("city", city);
		result.put("persona", persona);
		result.put("exclude20pct", exclude);
		result.put("requested", count);
		result.put("size", route.size());
		result.put("storeIds", ids(route));
		result.put("fallback", fallback);
		result.put("commonMeanScore",
			route.stream().mapToDouble(row -> common.get(row.storeId()).score()).average().orElse(0));
		result.put("commonMeanTaste",
			route.stream().mapToDouble(row -> common.get(row.storeId()).tasteFit()).average().orElse(0));
		result.put("totalDistanceM", totalDistance(route));
		result.put("maxHopM", maxHop);
		result.put("diameterM", diameter);
		result.put("outsideOldArea", route.stream().filter(row -> !oldIds.contains(row.storeId())).count());
		result.put("algorithmMs", elapsed);
		return result;
	}
}

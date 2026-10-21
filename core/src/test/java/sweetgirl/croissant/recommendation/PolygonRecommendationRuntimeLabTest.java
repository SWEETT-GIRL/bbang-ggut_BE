package sweetgirl.croissant.recommendation;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.recommendation.config.RecommendationProperties;
import sweetgirl.croissant.recommendation.repository.AreaChipStats;
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;
import sweetgirl.croissant.recommendation.repository.PolygonAreaBindingRepository;
import sweetgirl.croissant.recommendation.repository.RecommendationCandidateJdbcRepository;
import sweetgirl.croissant.recommendation.repository.StoreLabelStatsRepository;
import sweetgirl.croissant.recommendation.repository.TasteQuery;
import sweetgirl.croissant.recommendation.service.LabelCenterParams;
import sweetgirl.croissant.recommendation.service.PolygonCoursePlanner;

/** 로컬 런타임의 실제 폴리곤·점수 SQL·탐색기. 변형 검증은 임시 스키마 복제 행만 수정하고 롤백한다. */
@Tag("lab")
@EnabledIfEnvironmentVariable(named = "POLYGON_REGION_LOAD", matches = ".+")
class PolygonRecommendationRuntimeLabTest {
	private Connection connection;
	private JdbcTemplate jdbc;
	private RecommendationCandidateJdbcRepository candidates;
	private PolygonAreaBindingRepository bindings;
	private String version;
	private final ObjectMapper mapper = new ObjectMapper();

	@BeforeEach
	void setup() throws Exception {
		version = mapper.readTree(Files.readString(Path.of(System.getenv("POLYGON_REGION_LOAD")))).path("version_key")
			.asText();
		connection = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:5432/soboro", "sweetgirl", "soboro-lab");
		connection.setAutoCommit(false);
		jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
		jdbc.execute("SET LOCAL search_path TO region_polygon_runtime_742,public");
		NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbc);
		bindings = new PolygonAreaBindingRepository(named);
		candidates = new RecommendationCandidateJdbcRepository(named, new RecommendationProperties(),
			new LabelCenterParams(new StoreLabelStatsRepository(named)), mapper);
	}

	@AfterEach
	void rollback() throws Exception {
		if (connection != null) {
			connection.rollback();
			connection.close();
		}
	}

	@Test
	void actualProfilesExcludeRecordsAndKeepDistanceLimitsAcrossRetries() throws Exception {
		List<PolygonMemberCourseLabTest.MemberInput> members = jdbc.query("""
				WITH cohort AS (
					SELECT p.*, CASE WHEN p.axis_taste IS NULL THEN 'NO_AXIS'
						WHEN cardinality(coalesce(p.top_kinds,'{}'))>0 THEN 'AXIS_KINDS'
						ELSE 'AXIS_ONLY' END stratum FROM user_taste_profile p
				), ranked AS (
					SELECT *,row_number() OVER(PARTITION BY stratum ORDER BY member_id) position FROM cohort
				) SELECT * FROM ranked WHERE position<=6 ORDER BY stratum,position
				""", PolygonMemberCourseLabTest::member);
		assertThat(members).hasSize(18);
		int routes = 0;
		int complete = 0;
		long start = System.nanoTime();
		for (long areaId : List.of(7L, 8L, 9L)) {
			long binding = bindings.findReadyBinding(version, areaId).orElseThrow();
			for (var member : members) {
				Set<Long> recorded = new HashSet<>(jdbc.queryForList(
					"SELECT store_id FROM bread_record WHERE member_id=? AND deleted_at IS NULL", Long.class,
					member.memberId()));
				Set<Long> shown = new HashSet<>();
				double lat = 37;
				double lng = 127;
				Integer radius = null;
				for (int retry = 0; retry < 3; retry++) {
					List<CandidateStoreRow> rows = candidates.findPolygonCandidates(member.memberId(), member.taste(),
						binding,
						lat, lng, radius, shown);
					assertThat(rows)
						.noneMatch(row -> recorded.contains(row.storeId()) || shown.contains(row.storeId()));
					List<CandidateStoreRow> route = new PolygonCoursePlanner().plan(rows, 4, 1500, 1500, null,
						Set.of());
					int total = 0;
					for (int i = 1; i < route.size(); i++) {
						total += PolygonCoursePlanner.distance(route.get(i - 1), route.get(i));
					}
					assertThat(total).isLessThanOrEqualTo(1500);
					if (route.size() == 4) {
						complete++;
					}
					routes++;
					shown.addAll(route.stream().map(CandidateStoreRow::storeId).toList());
					if (retry == 0 && !route.isEmpty()) {
						lat = route.get(0).latitude();
						lng = route.get(0).longitude();
						radius = 1517;
					}
				}
			}
		}
		assertThat(routes).isEqualTo(162);
		String output = System.getenv("POLYGON_RUNTIME_REPORT");
		if (output != null) {
			Files.writeString(Path.of(output + ".profiles.json"), mapper.writeValueAsString(Map.of(
				"profiles", members.size(), "routes", routes, "complete", complete,
				"elapsedMs", (System.nanoTime() - start) / 1_000_000)));
		}
	}

	@Test
	void allAreasUseIdenticalPolygonMembershipForCountsAndRoutes() throws Exception {
		List<Map<String, Object>> report = new ArrayList<>();
		List<Map<String, Object>> areas = jdbc.queryForList("SELECT * FROM course_area WHERE is_active ORDER BY id");
		assertThat(areas).hasSize(59);
		Set<String> chips = new HashSet<>();
		for (Map<String, Object> area : areas) {
			long id = ((Number)area.get("id")).longValue();
			long binding = bindings.findReadyBinding(version, id).orElseThrow();
			double lat = ((Number)area.get("center_lat")).doubleValue();
			double lng = ((Number)area.get("center_lng")).doubleValue();
			long started = System.nanoTime();
			List<CandidateStoreRow> rows = candidates.findPolygonCandidates(-1L, TasteQuery.empty(), binding, lat, lng,
				null, Set.of());
			long candidateNanos = System.nanoTime() - started;
			AreaChipStats stats = candidates.findPolygonChipStats(-1L, null, TasteQuery.empty(), id, binding);
			assertThat(stats.storeCount()).as("area %s", id).isEqualTo(rows.size());
			assertThat(rows).extracting(CandidateStoreRow::storeId).doesNotHaveDuplicates();
			List<Long> independent = jdbc.queryForList("""
					SELECT s.id FROM store s JOIN store_profile p ON p.store_id=s.id
					JOIN course_area_region_bindings binding ON binding.id=?
					WHERE s.status='ACTIVE' AND p.bakery_score>=0.5
					AND EXISTS(SELECT 1 FROM region_emd_boundaries b WHERE b.version_id=binding.version_id
						AND ST_Covers(b.geom,s.location::geometry))
					AND NOT EXISTS(SELECT 1 FROM region_emd_boundaries b WHERE b.version_id=binding.version_id
						AND ST_Covers(b.geom,s.location::geometry) AND NOT b.legal_code=ANY(binding.legal_codes))
					""", Long.class, binding);
			assertThat(rows).extracting(CandidateStoreRow::storeId).containsExactlyInAnyOrderElementsOf(independent);
			long plannerStart = System.nanoTime();
			PolygonCoursePlanner planner = new PolygonCoursePlanner();
			List<Integer> sizes = new ArrayList<>();
			for (int count : List.of(1, 4, 6)) {
				int budget = count == 1 ? 1500 : (count - 1) * 500;
				List<CandidateStoreRow> route = planner.plan(rows, count, ((Number)area.get("max_hop_m")).intValue(),
					budget, null, Set.of());
				int total = 0;
				for (int i = 1; i < route.size(); i++) {
					total += PolygonCoursePlanner.distance(route.get(i - 1), route.get(i));
				}
				assertThat(total).isLessThanOrEqualTo(budget);
				assertThat(route.size()).isLessThanOrEqualTo(count);
				sizes.add(route.size());
			}
			chips.add(area.get("sido") + ":" + area.get("passport_spot"));
			report.add(Map.of("areaId", id, "chip", area.get("passport_spot"), "candidateCount", rows.size(),
				"routeSizes1_4_6", sizes, "candidateMs", candidateNanos / 1_000_000,
				"threePlansMs", (System.nanoTime() - plannerStart) / 1_000_000, "validationMs",
				(System.nanoTime() - started) / 1_000_000));
		}
		assertThat(chips).hasSize(48);
		String output = System.getenv("POLYGON_RUNTIME_REPORT");
		if (output != null) {
			Files.writeString(Path.of(output), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
		}
	}

	@Test
	void changedCoordinatesEligibilityAndBindingIdentityAreRechecked() {
		long binding = bindings.findReadyBinding(version, 7L).orElseThrow();
		List<CandidateStoreRow> original = candidates.findPolygonCandidates(-1L, TasteQuery.empty(), binding, 37, 127,
			null, Set.of());
		assertThat(original).hasSizeGreaterThan(4);
		List<Long> ids = original.subList(0, 4).stream().map(CandidateStoreRow::storeId).toList();
		String schema = "polygon_candidate_test_" + UUID.randomUUID().toString().replace("-", "");
		jdbc.execute("CREATE SCHEMA " + schema);
		jdbc.execute("SET LOCAL search_path TO " + schema + ",region_polygon_runtime_742,public");
		String idList = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
		jdbc.execute("CREATE TABLE store AS SELECT * FROM public.store WHERE id IN (" + idList + ")");
		jdbc.execute("CREATE TABLE course_area AS SELECT * FROM public.course_area");
		jdbc.execute(
			"CREATE TABLE store_profile AS SELECT * FROM public.store_profile WHERE store_id IN (" + idList + ")");
		jdbc.update("UPDATE store SET location=ST_SetSRID(ST_Point(127,37),4326) WHERE id=?", ids.get(0));
		jdbc.update("UPDATE store SET status='INACTIVE' WHERE id=?", ids.get(1));
		jdbc.update("UPDATE store_profile SET bakery_score=0.1 WHERE store_id=?", ids.get(2));
		assertThat(candidates.findPolygonCandidates(-1L, TasteQuery.empty(), binding, 37, 127, null, Set.of()))
			.extracting(CandidateStoreRow::storeId).containsExactly(ids.get(3));
		assertThat(
			candidates.findPolygonCandidates(-1L, TasteQuery.empty(), binding, 37, 127, null, Set.of(ids.get(3))))
			.isEmpty();
		assertThat(candidates.findPolygonChipStats(-1L, null, TasteQuery.empty(), 7L, binding).storeCount())
			.isEqualTo(1);
		jdbc.update("UPDATE course_area SET passport_spot='변경된 지역' WHERE id=7");
		assertThat(bindings.findReadyBinding(version, 7L)).isEmpty();
	}
}

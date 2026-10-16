package sweetgirl.croissant.store.region;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 명시한 로컬 경계 버전만 별도 랩 스키마에 저장한다. public.store는 잠금·조회만 한다. */
@Tag("lab")
@EnabledIfEnvironmentVariable(named = "POLYGON_REGION_LOAD", matches = ".+")
class StoreRegionNationwideBackfillLabTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@Test
	void persistNationwideResolutionAndCompareEveryStoreWithIndependentAudit() throws Exception {
		JsonNode load = JSON.readTree(Files.readString(Path.of(System.getenv("POLYGON_REGION_LOAD"))));
		JsonNode audit = JSON.readTree(Files.readString(Path.of(System.getenv("POLYGON_REGION_EVIDENCE"))));
		Map<Long, JsonNode> expected = new HashMap<>();
		audit.path("stores").forEach(row -> expected.put(row.path("id").asLong(), row));
		try (Connection connection = DriverManager.getConnection(
			"jdbc:postgresql://127.0.0.1:5432/soboro", "sweetgirl", "soboro-lab")) {
			connection.setAutoCommit(false);
			JdbcTemplate jdbcTemplate = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
			assertThat(
				jdbcTemplate.queryForObject("SELECT system_identifier::text FROM pg_control_system()", String.class))
				.isEqualTo(audit.path("db_system_identifier").asText());
			assertThat(load.path("runtime_schema").asText()).isEqualTo("region_polygon_runtime_742");
			jdbcTemplate.execute("SET search_path TO region_polygon_runtime_742, public");
			String before = storeFingerprint(jdbcTemplate);
			StoreRegionResolutionRepository repository = new StoreRegionResolutionRepository(
				new NamedParameterJdbcTemplate(jdbcTemplate));
			StoreRegionResolutionProperties properties = new StoreRegionResolutionProperties();
			properties.setEnabled(true);
			properties.setVersionKey(load.path("version_key").asText());
			StoreRegionResolutionCommandService service = new StoreRegionResolutionCommandService(repository,
				properties);
			long started = System.nanoTime();
			int total = 0;
			int batches = 0;
			int changed;
			do {
				changed = service.refreshChangedStores();
				connection.commit();
				total += changed;
				batches++;
				assertThat(batches).isLessThan(1000);
			} while (changed > 0);
			long elapsedMs = (System.nanoTime() - started) / 1_000_000;
			assertThat(storeFingerprint(jdbcTemplate)).isEqualTo(before);
			List<Map<String, Object>> counts = jdbcTemplate.queryForList("""
					SELECT legal_status,count(*) stores,count(*) FILTER(WHERE scope_code IS NOT NULL) resolved_scope
					FROM store_region_resolutions r JOIN region_boundary_versions v ON v.id=r.version_id
					WHERE v.version_key=? GROUP BY legal_status ORDER BY legal_status
					""", properties.getVersionKey());
			RowMapper<Long> mapper = (result, index) -> {
				long id = result.getLong("store_id");
				JsonNode row = expected.get(id);
				assertThat(row).isNotNull();
				List<String> codes = JSON.convertValue(row.path("codes"),
					JSON.getTypeFactory().constructCollectionType(List.class, String.class));
				assertThat((String[])result.getArray("legal_codes").getArray()).containsExactlyElementsOf(codes);
				assertThat(result.getString("scope_code") != null).isEqualTo(row.path("unique_app_scope").asBoolean());
				return id;
			};
			List<Long> checked = jdbcTemplate.query("""
					SELECT r.store_id,r.legal_codes,r.scope_code FROM store_region_resolutions r
					JOIN region_boundary_versions v ON v.id=r.version_id WHERE v.version_key=? ORDER BY r.store_id
					""", mapper, properties.getVersionKey());
			assertThat(checked).hasSize(expected.size());
			assertThat(service.refreshChangedStores()).isZero();
			connection.commit();
			Map<String, Object> report = new LinkedHashMap<>();
			report.put("versionKey", properties.getVersionKey());
			report.put("matchedAuditStores", checked.size());
			report.put("updatedThisRun", total);
			report.put("batchesIncludingFinalEmpty", batches);
			report.put("elapsedMs", elapsedMs);
			report.put("counts", counts);
			report.put("storeFingerprintUnchanged", true);
			report.put("productionApplied", false);
			Files.writeString(Path.of(System.getenv("POLYGON_REGION_REPORT")),
				JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
		}
	}

	private String storeFingerprint(JdbcTemplate jdbcTemplate) {
		return jdbcTemplate.queryForObject("""
				SELECT md5(string_agg(concat_ws('|',id,region_1st,region_2nd,region_3rd,
					ST_AsEWKB(location::geometry)::text,updated_at::text),';' ORDER BY id)) FROM public.store
				""", String.class);
	}
}

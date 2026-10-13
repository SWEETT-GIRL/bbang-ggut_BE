package sweetgirl.croissant.store.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** 임시 스키마의 공간 픽스처만 쓰고 DDL을 포함해 롤백한다. public.store는 수정하지 않는다. */
@Tag("lab")
class StoreRegionResolutionRepositoryLabTest {

	private Connection connection;
	private JdbcTemplate jdbcTemplate;
	private StoreRegionResolutionRepository repository;

	@BeforeEach
	void setUp() throws Exception {
		connection = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:5432/soboro", "sweetgirl", "soboro-lab");
		connection.setAutoCommit(false);
		jdbcTemplate = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
		String schema = "region_resolution_test_" + UUID.randomUUID().toString().replace("-", "");
		jdbcTemplate.execute("CREATE SCHEMA " + schema);
		jdbcTemplate.execute("SET LOCAL search_path TO " + schema + ", public");
		jdbcTemplate.execute("CREATE TABLE store (id bigint PRIMARY KEY, location geography(Point,4326))");
		jdbcTemplate.execute(Files.readString(Path.of(
			"src/main/resources/db/migration/V20261013190000__create_store_region_boundaries.sql")));
		repository = new StoreRegionResolutionRepository(new NamedParameterJdbcTemplate(jdbcTemplate));
		version("v1");
		seal("v1");
		jdbcTemplate.execute("""
				INSERT INTO store VALUES
					(1,ST_SetSRID(ST_Point(126.5,36.5),4326)),
					(2,ST_SetSRID(ST_Point(127,36.5),4326)),
					(3,ST_SetSRID(ST_Point(127.75,36.5),4326)),
					(4,ST_SetSRID(ST_Point(130,36.5),4326)), (5,NULL)
				""");
	}

	@AfterEach
	void tearDown() throws Exception {
		if (connection != null) {
			connection.rollback();
			connection.close();
		}
	}

	@Test
	void cannotInsertAlreadyReadyVersion() {
		assertThatThrownBy(() -> jdbcTemplate.update(
			"""
					INSERT INTO region_boundary_versions(
					version_key,source_date,reference_sha256,projection_version,metadata,ready)
					VALUES ('bypass','2026-10-13',repeat('a',64),'v1','{}',true)
					"""))
			.isInstanceOf(DataAccessException.class).hasMessageContaining("staged");
	}

	@Test
	void distinguishesLegalAmbiguityFromCommonScopeAndDoesNotSnap() {
		assertThat(repository.refreshChanged("v1", 10)).isEqualTo(5);
		assertThat(repository.findCurrentStoreIds("v1", "1100000000")).containsExactly(1L, 2L);
		assertThat(repository.findCurrentStoreIds("v1", "2200000000")).isEmpty();
		assertThat(jdbcTemplate.queryForList("SELECT legal_status FROM store_region_resolutions ORDER BY store_id",
			String.class))
			.containsExactly("RESOLVED", "AMBIGUOUS", "AMBIGUOUS", "NO_MATCH", "NO_POINT");
		assertThat(repository.refreshChanged("v1", 10)).isZero();
	}

	@Test
	void boundsBatchAndProgressesPastUnresolvedStores() {
		assertThat(repository.refreshChanged("v1", 2)).isEqualTo(2);
		assertThat(repository.refreshChanged("v1", 2)).isEqualTo(2);
		assertThat(repository.refreshChanged("v1", 2)).isEqualTo(1);
		assertThat(repository.refreshChanged("v1", 2)).isZero();
	}

	@Test
	void changedCoordinatesStopMatchingBeforeRefreshAndAreRecomputed() {
		repository.refreshChanged("v1", 10);
		jdbcTemplate.update("UPDATE store SET location=ST_SetSRID(ST_Point(130,36.5),4326) WHERE id=1");
		assertThat(repository.findCurrentStoreIds("v1", "1100000000")).containsExactly(2L);
		jdbcTemplate.update("UPDATE store SET location=ST_SetSRID(ST_Point(126.5,36.5),4326) WHERE id=5");
		assertThat(repository.refreshChanged("v1", 10)).isEqualTo(2);
		assertThat(repository.findCurrentStoreIds("v1", "1100000000")).containsExactly(2L, 5L);
		jdbcTemplate.update("UPDATE store SET location=NULL WHERE id=2");
		assertThat(repository.findCurrentStoreIds("v1", "1100000000")).containsExactly(5L);
		assertThat(repository.refreshChanged("v1", 10)).isEqualTo(1);
	}

	@Test
	void newVersionIsIsolatedAndCannotBeUsedBeforeReady() {
		repository.refreshChanged("v1", 10);
		version("v2");
		assertThat(repository.isReady("v2")).isFalse();
		assertThat(repository.refreshChanged("v2", 10)).isZero();
		assertThat(repository.findCurrentStoreIds("v2", "1100000000")).isEmpty();
		seal("v2");
		assertThat(repository.isReady("v2")).isTrue();
		assertThat(repository.refreshChanged("v2", 10)).isEqualTo(5);
		assertThat(repository.findCurrentStoreIds("v2", "1100000000")).containsExactly(1L, 2L);
		assertThat(repository.isReady("missing")).isFalse();
	}

	@Test
	void readyGeometryCannotChangeBehindStoredResolutions() {
		assertThatThrownBy(() -> jdbcTemplate.update("UPDATE region_emd_boundaries SET official_name='changed'"))
			.isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
	}

	@Test
	void readyVersionCannotBeReopened() {
		assertThatThrownBy(() -> jdbcTemplate.update("UPDATE region_boundary_versions SET ready=false"))
			.isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
	}

	private void version(String key) {
		jdbcTemplate.update(
			"""
						INSERT INTO region_boundary_versions
					(version_key,source_date,reference_sha256,projection_version,metadata)
						VALUES (?,DATE '2026-09-09',repeat('a',64),'test-v1','{}')
						""",
			key);
		Long id = jdbcTemplate.queryForObject("SELECT id FROM region_boundary_versions WHERE version_key=?", Long.class,
			key);
		jdbcTemplate.update("""
				INSERT INTO region_app_scopes(version_id,scope_code,name,level)
				VALUES (?,'1100000000','같은 도시','CITY'),(?,'2200000000','다른 도시','CITY')
				""", id, id);
		boundary(id, "1111111100", "1100000000", 126, 127);
		boundary(id, "1111111200", "1100000000", 127, 128);
		boundary(id, "2211111100", "2200000000", 127.5, 128.5);
	}

	private void boundary(long versionId, String code, String scope, double left, double right) {
		new NamedParameterJdbcTemplate(jdbcTemplate).update("""
				INSERT INTO region_emd_boundaries(version_id,legal_code,official_name,ancestry,scope_code,geom)
				VALUES (:version,:code,'테스트 동','[]',:scope,ST_Multi(ST_MakeEnvelope(:left,36,:right,37,4326)))
				""", Map.of("version", versionId, "code", code, "scope", scope, "left", left, "right", right));
	}

	private void seal(String key) {
		jdbcTemplate.update("UPDATE region_boundary_versions SET ready=true WHERE version_key=?", key);
	}
}

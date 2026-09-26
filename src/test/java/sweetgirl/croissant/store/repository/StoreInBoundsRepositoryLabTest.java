package sweetgirl.croissant.store.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import sweetgirl.croissant.config.persistence.JpaConfig;
import sweetgirl.croissant.store.dto.projection.StoreInBoundsProjection;

/**
 * 지도 영역 조회 SQL 을 랩 PG(운영 덤프, PostGIS)에서 돈다. H2 로는 geography 상자(&&)·<-> 를 검증할 수 없다.
 * 실행: {@code ./gradlew test -PlabTests}. 영역은 성수 일대 — 가게가 limit 보다 많아 자르기와 total 을 함께 본다.
 */
@Tag("lab")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
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
class StoreInBoundsRepositoryLabTest {

	private static final double SW_LAT = 37.53;
	private static final double SW_LNG = 127.03;
	private static final double NE_LAT = 37.56;
	private static final double NE_LNG = 127.07;
	private static final double CENTER_LAT = 37.5446;
	private static final double CENTER_LNG = 127.0557;

	/** 인덱스 상자 없이 위경도만으로 센 영역 안 ACTIVE 가게 — 조회가 빠뜨린 게 없는지의 기준 */
	private static final String PLAIN_COUNT_SQL = """
			SELECT count(*) FROM store s WHERE s.status = 'ACTIVE'
			AND ST_Y(s.location::geometry) BETWEEN ? AND ? AND ST_X(s.location::geometry) BETWEEN ? AND ?
			""";

	@Autowired
	private StoreRepository storeRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("영역 상자(&&)로 좁혀도 위경도로 센 수와 같다 — 구면 상자가 모서리 가게를 빠뜨리지 않는다")
	void total_matchesPlainLatLngCount() {
		Long plainCount = jdbcTemplate.queryForObject(PLAIN_COUNT_SQL, Long.class, SW_LAT, NE_LAT, SW_LNG, NE_LNG);

		List<StoreInBoundsProjection> rows = storeRepository.findStoresInBounds(SW_LAT, SW_LNG, NE_LAT, NE_LNG,
			CENTER_LAT, CENTER_LNG, 300);
		long total = storeRepository.countStoresInBounds(SW_LAT, SW_LNG, NE_LAT, NE_LNG, 1_000_000);

		assertThat(plainCount).isPositive();
		assertThat(rows).isNotEmpty();
		assertThat(total).isEqualTo(plainCount);
		assertThat(rows).hasSize((int)Math.min(plainCount, 300));
	}

	@Test
	@DisplayName("영역 안 가게 수는 cap 에서 멈춘다")
	void count_stopsAtCap() {
		Long plainCount = jdbcTemplate.queryForObject(PLAIN_COUNT_SQL, Long.class, SW_LAT, NE_LAT, SW_LNG, NE_LNG);

		assertThat(storeRepository.countStoresInBounds(SW_LAT, SW_LNG, NE_LAT, NE_LNG, 5))
			.isEqualTo(Math.min(plainCount, 5));
	}

	@Test
	@DisplayName("<-> 정렬 결과가 예전 쿼리(count(*) OVER() + 같은 정렬)와 id·순서까지 같다")
	void rows_matchPreviousQuery() {
		List<Long> previous = jdbcTemplate.query("""
				SELECT s.id, COUNT(*) OVER () AS total
				FROM store s
				WHERE s.status = 'ACTIVE'
				AND s.location && ST_MakeEnvelope(?, ?, ?, ?, 4326)::geography
				AND ST_Y(s.location::geometry) BETWEEN ? AND ?
				AND ST_X(s.location::geometry) BETWEEN ? AND ?
				ORDER BY s.location <-> ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, s.id
				LIMIT 150
				""", (resultSet, rowNum) -> resultSet.getLong("id"),
			SW_LNG, SW_LAT, NE_LNG, NE_LAT, SW_LAT, NE_LAT, SW_LNG, NE_LNG, CENTER_LNG, CENTER_LAT);

		List<Long> current = storeRepository.findStoresInBounds(SW_LAT, SW_LNG, NE_LAT, NE_LNG,
			CENTER_LAT, CENTER_LNG, 150).stream().map(StoreInBoundsProjection::getStoreId).toList();

		assertThat(current).isEqualTo(previous);
	}

	@Test
	@DisplayName("limit 곳만, 기준점에서 가까운 순으로 — 모두 영역 안 ACTIVE 가게다")
	void rows_areInsideAndNearestFirst() {
		List<StoreInBoundsProjection> rows = storeRepository.findStoresInBounds(SW_LAT, SW_LNG, NE_LAT, NE_LNG,
			CENTER_LAT, CENTER_LNG, 20);

		assertThat(rows).hasSizeLessThanOrEqualTo(20);
		List<Long> ids = rows.stream().map(StoreInBoundsProjection::getStoreId).toList();
		List<Double> distances = ids.stream()
			.map(id -> jdbcTemplate.queryForObject("""
					SELECT ST_Distance(location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) FROM store
					WHERE id = ? AND status = 'ACTIVE'
					AND ST_Y(location::geometry) BETWEEN ? AND ? AND ST_X(location::geometry) BETWEEN ? AND ?
					""", Double.class, CENTER_LNG, CENTER_LAT, id, SW_LAT, NE_LAT, SW_LNG, NE_LNG))
			.toList();
		// <-> 는 구면, ST_Distance 는 회전타원체라 1m 안쪽으로 순서가 뒤집힐 수 있다 — 1m 여유
		for (int i = 1; i < distances.size(); i++) {
			assertThat(distances.get(i)).isGreaterThanOrEqualTo(distances.get(i - 1) - 1.0);
		}
	}
}

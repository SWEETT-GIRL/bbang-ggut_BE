package sweetgirl.croissant.dessert.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import sweetgirl.croissant.config.persistence.JpaConfig;
import sweetgirl.croissant.dessert.dto.projection.DessertStoreProjection;

/**
 * 디저트 판매처의 지도 영역 조회 SQL 을 랩 PG 에서 돈다. 편의점이 가장 촘촘해(전국 5만) 영역 경계를 보기 좋다.
 * 실행: {@code ./gradlew test -PlabTests}.
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
class DessertInBoundsRepositoryLabTest {

	private static final double SW_LAT = 37.55;
	private static final double SW_LNG = 126.96;
	private static final double NE_LAT = 37.58;
	private static final double NE_LNG = 127.00;
	private static final double CENTER_LAT = 37.5665;
	private static final double CENTER_LNG = 126.978;

	@Autowired
	private ConvenienceStoreRepository convenienceStoreRepository;
	@Autowired
	private DessertItemRepository dessertItemRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("편의점 지점은 영역 안에서만, 가까운 순으로, 위경도로 센 것과 같은 수가 나온다")
	void convenienceBranches_insideBoundsNearestFirst() {
		List<String> brands = jdbcTemplate.queryForList(
			"SELECT brand FROM convenience_store GROUP BY brand ORDER BY count(*) DESC LIMIT 1", String.class);
		Assumptions.assumeFalse(brands.isEmpty(), "랩에 편의점 지점이 없다");
		String brand = brands.get(0);
		Long plainCount = jdbcTemplate.queryForObject("""
				SELECT count(*) FROM convenience_store c WHERE c.brand = ?
				AND ST_Y(c.location::geometry) BETWEEN ? AND ? AND ST_X(c.location::geometry) BETWEEN ? AND ?
				""", Long.class, brand, SW_LAT, NE_LAT, SW_LNG, NE_LNG);

		List<DessertStoreProjection> branches = convenienceStoreRepository.findBranchesInBounds(brand,
			SW_LAT, SW_LNG, NE_LAT, NE_LNG, CENTER_LAT, CENTER_LNG, PageRequest.of(0, 300));

		assertThat(branches).hasSize((int)Math.min(plainCount, 300));
		assertInsideAndNearestFirst(branches);
	}

	@Test
	@DisplayName("동네 가게 판매처도 영역 안에서만, 가까운 순으로 나온다")
	void sellingStores_insideBoundsNearestFirst() {
		List<Long> dessertIds = jdbcTemplate.queryForList(
			"SELECT dessert_item_id FROM dessert_item_store WHERE status = 'ACTIVE'"
				+ " GROUP BY dessert_item_id ORDER BY count(*) DESC LIMIT 1",
			Long.class);
		Assumptions.assumeFalse(dessertIds.isEmpty(), "랩에 디저트 판매처가 없다");

		List<DessertStoreProjection> stores = dessertItemRepository.findSellingStoresInBounds(dessertIds.get(0),
			33.0, 124.5, 38.7, 131.0, CENTER_LAT, CENTER_LNG, PageRequest.of(0, 300));

		assertThat(stores).isNotEmpty();
		assertInsideAndNearestFirst(stores, 33.0, 124.5, 38.7, 131.0);
	}

	private static void assertInsideAndNearestFirst(List<DessertStoreProjection> rows) {
		assertInsideAndNearestFirst(rows, SW_LAT, SW_LNG, NE_LAT, NE_LNG);
	}

	private static void assertInsideAndNearestFirst(List<DessertStoreProjection> rows, double swLat, double swLng,
			double neLat, double neLng) {
		for (int i = 0; i < rows.size(); i++) {
			DessertStoreProjection row = rows.get(i);
			assertThat(row.getLatitude()).isBetween(swLat, neLat);
			assertThat(row.getLongitude()).isBetween(swLng, neLng);
			if (i > 0) {
				// 정렬은 <->(구면), distanceMeters 는 ST_Distance(회전타원체)라 거의 같은 거리끼리는 뒤집힐 수 있다 — 0.5% 여유
				double previous = rows.get(i - 1).getDistanceMeters();
				assertThat(row.getDistanceMeters()).isGreaterThanOrEqualTo(previous - Math.max(1.0, previous * 0.005));
			}
		}
	}
}

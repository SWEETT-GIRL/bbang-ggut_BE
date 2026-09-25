package sweetgirl.croissant.dessert.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.dessert.dto.projection.DessertStoreProjection;
import sweetgirl.croissant.dessert.entity.DessertItem;

public interface DessertItemRepository extends JpaRepository<DessertItem, Long> {

	List<DessertItem> findByActiveTrue();

	/**
	 * 이 디저트를 파는 빵긋 가게, 가까운 순.
	 *
	 * <p>GENERIC(피자설기처럼 동네 가게가 각자 파는 것)과 CHAIN(브랜드 지점을 store 로 매칭해 둔 것)이
	 * 같은 표에 있어 한 쿼리로 읽는다. 편의점만 표가 다르다.
	 *
	 * <p>ST_DWithin 을 먼저 걸어 store.location 의 GIST 인덱스를 태운다. 반경 없이 전국을 정렬하면
	 * 피자설기 하나만 해도 수백 곳을 다 재게 된다.
	 */
	@Query(value = """
			SELECT
			s.id AS "storeId",
			s.name AS "name",
			s.address AS "address",
			ST_Y(s.location::geometry) AS "latitude",
			ST_X(s.location::geometry) AS "longitude",
			ST_Distance(s.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS "distanceMeters",
			dessert_store.grade AS "grade",
			dessert_store.evidence_count AS "evidenceCount",
			dessert_store.last_evidence_at AS "lastEvidenceAt"
			FROM dessert_item_store dessert_store
			JOIN store s ON s.id = dessert_store.store_id
			WHERE dessert_store.dessert_item_id = :dessertItemId
			AND dessert_store.status = 'ACTIVE'
			AND s.status = 'ACTIVE'
			AND ST_DWithin(s.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radiusMeters)
			ORDER BY "distanceMeters" ASC
			""", nativeQuery = true)
	List<DessertStoreProjection> findSellingStoresNearby(
			@Param("dessertItemId") Long dessertItemId,
			@Param("lat") double latitude,
			@Param("lng") double longitude,
			@Param("radiusMeters") double radiusMeters,
			Pageable pageable);

	/**
	 * 이 디저트를 파는 빵긋 가게 중 지도 영역(사각형) 안, 기준점에서 가까운 순.
	 *
	 * <p>{@code &&}(영역 상자 겹침)로 GIST 인덱스를 태우고, 위경도 BETWEEN 으로 앱이 보는 사각형과 정확히 맞춘다.
	 */
	@Query(value = """
			SELECT
			s.id AS "storeId",
			s.name AS "name",
			s.address AS "address",
			ST_Y(s.location::geometry) AS "latitude",
			ST_X(s.location::geometry) AS "longitude",
			ST_Distance(s.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS "distanceMeters",
			dessert_store.grade AS "grade",
			dessert_store.evidence_count AS "evidenceCount",
			dessert_store.last_evidence_at AS "lastEvidenceAt"
			FROM dessert_item_store dessert_store
			JOIN store s ON s.id = dessert_store.store_id
			WHERE dessert_store.dessert_item_id = :dessertItemId
			AND dessert_store.status = 'ACTIVE'
			AND s.status = 'ACTIVE'
			AND s.location && ST_MakeEnvelope(:swLng, :swLat, :neLng, :neLat, 4326)::geography
			AND ST_Y(s.location::geometry) BETWEEN :swLat AND :neLat
			AND ST_X(s.location::geometry) BETWEEN :swLng AND :neLng
			ORDER BY "distanceMeters" ASC
			""", nativeQuery = true)
	List<DessertStoreProjection> findSellingStoresInBounds(
			@Param("dessertItemId") Long dessertItemId,
			@Param("swLat") double swLat,
			@Param("swLng") double swLng,
			@Param("neLat") double neLat,
			@Param("neLng") double neLng,
			@Param("lat") double latitude,
			@Param("lng") double longitude,
			Pageable pageable);
}

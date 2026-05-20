package sweetgirl.croissant.store.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.store.dto.projection.DistrictStoreProjection;
import sweetgirl.croissant.store.dto.projection.NearbyStoreProjection;
import sweetgirl.croissant.store.dto.projection.TopSubscribedStoreProjection;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.enums.StoreStatus;

public interface StoreRepository extends JpaRepository<Store, Long> {

	Slice<Store> findByNameContainingAndStatus(String name, StoreStatus status, Pageable pageable);

	Page<Store> findByNameContainingIgnoreCase(String name, Pageable pageable);

	Page<Store> findByStatus(StoreStatus status, Pageable pageable);

	Page<Store> findByNameContainingIgnoreCaseAndStatus(String name, StoreStatus status, Pageable pageable);

	@Query(value = """
				SELECT
					s.id,
					s.name,
					s.address,
					ST_Distance(s.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS distance_meters,
					COUNT(ss.id) AS subscribe_count
				FROM store s
				LEFT JOIN subscribed_store ss ON ss.store_id = s.id
				WHERE s.district = CAST(:district AS VARCHAR)
				AND s.status = 'ACTIVE'
				GROUP BY s.id
			""", nativeQuery = true)
	Slice<DistrictStoreProjection> findStoresByDistrictWithStats(
			@Param("district") String district,
			@Param("lng") double longitude,
			@Param("lat") double latitude,
			Pageable pageable);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			INSERT INTO store (
					name, address, district, location, phone, kakao_place_id, source, status, created_at, updated_at)
			VALUES (:name, :address, CAST(:district AS VARCHAR),
					ST_SetSRID(ST_MakePoint(:x, :y), 4326)::geography, :phone, :kakaoPlaceId,
					'KAKAO_IMPORTED', 'ACTIVE', NOW(), NOW())
			ON CONFLICT (kakao_place_id) DO NOTHING
			""", nativeQuery = true)
	void insertIfNotExistsByKakaoPlaceId(
			@Param("name") String name,
			@Param("address") String address,
			@Param("district") String district,
			@Param("x") double longitude,
			@Param("y") double latitude,
			@Param("phone") String phone,
			@Param("kakaoPlaceId") String kakaoPlaceId);

	@Query(value = """
			SELECT * FROM store
			WHERE status = 'ACTIVE'
			AND category = 'WESTERN'
			AND name ILIKE CONCAT('%', :keyword, '%')
			""", nativeQuery = true)
	Slice<Store> findActiveWesternStoresByKeyword(@Param("keyword") String keyword, Pageable pageable);

	@Query(value = """
				SELECT
					s.id,
					s.name,
					s.district,
					ST_Distance( s.location , ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS distance_meters
				FROM store s
				WHERE ST_DWithin( s.location , ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography , :radius)
				AND s.status = 'ACTIVE'
				ORDER BY distance_meters ASC
			""", nativeQuery = true)
	Slice<NearbyStoreProjection> findNearbyStores(@Param("lng") double longitude, @Param("lat") double latitude,
			@Param("radius") double radiusMeters, Pageable pageable);

	@Query(value = """
				SELECT
					s.id,
					s.name,
					s.district,
					COUNT(ss.id) AS subscribe_count,
					ST_Distance( s.location , ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS distance_meters
				FROM store s
				LEFT JOIN subscribed_store ss ON ss.store_id = s.id
				WHERE s.status = 'ACTIVE'
				GROUP BY s.id
				ORDER BY subscribe_count DESC
				LIMIT :limit
			""", nativeQuery = true)
	List<TopSubscribedStoreProjection> findTopSubscribedStores(@Param("lng") double longitude,
			@Param("lat") double latitude, @Param("limit") int limit);

	boolean existsById(Long storeId);

	List<Store> findByKakaoPlaceIdIn(List<String> kakaoPlaceIds);
}

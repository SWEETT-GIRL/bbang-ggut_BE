package sweetgirl.croissant.store.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.store.dto.projection.NearbyStoreProjection;
import sweetgirl.croissant.store.dto.projection.RegionByDistanceProjection;
import sweetgirl.croissant.store.dto.projection.RegionBySubscribeProjection;
import sweetgirl.croissant.store.dto.projection.TopSubscribedStoreProjection;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.enums.StoreStatus;

public interface StoreRepository extends JpaRepository<Store, Long> {

	Slice<Store> findByNameContainingAndStatus(String name, StoreStatus status, Pageable pageable);

	Page<Store> findByNameContainingIgnoreCase(String name, Pageable pageable);

	Page<Store> findByStatus(StoreStatus status, Pageable pageable);

	Page<Store> findByNameContainingIgnoreCaseAndStatus(String name, StoreStatus status, Pageable pageable);

	// ── region_3rd (동) 기준 — region_2nd, region_1st AND 조건 포함 ─────────────

	@Query(value = """
				SELECT
					s.id,
					s.name,
					s.address,
					ST_Distance(s.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS distance_meters
				FROM store s
				WHERE s.region_3rd LIKE CONCAT('%', :r3, '%')
				AND s.region_2nd LIKE CONCAT('%', :r2, '%')
				AND s.region_1st LIKE CONCAT('%', :r1, '%')
				AND s.status = 'ACTIVE'
				ORDER BY distance_meters ASC
			""", nativeQuery = true)
	Slice<RegionByDistanceProjection> findStoresByRegion3rdOrderByDistance(
			@Param("r3") String r3,
			@Param("r2") String r2,
			@Param("r1") String r1,
			@Param("lng") double longitude,
			@Param("lat") double latitude,
			Pageable pageable);

	@Query("""
				SELECT s.id AS id, s.name AS name, s.address AS address, COUNT(ss.id) AS subscribeCount
				FROM Store s
				LEFT JOIN SubscribedStore ss ON ss.store = s
				WHERE s.region3rd LIKE CONCAT('%', :r3, '%')
				AND s.region2nd LIKE CONCAT('%', :r2, '%')
				AND s.region1st LIKE CONCAT('%', :r1, '%')
				AND s.status = 'ACTIVE'
				GROUP BY s.id, s.name, s.address
				ORDER BY COUNT(ss.id) DESC
			""")
	Slice<RegionBySubscribeProjection> findStoresByRegion3rdOrderBySubscribeCount(
			@Param("r3") String r3,
			@Param("r2") String r2,
			@Param("r1") String r1,
			Pageable pageable);

	// ── region_2nd (구/시) 기준 — region_1st AND 조건 포함 ────────────────────

	@Query(value = """
				SELECT
					s.id,
					s.name,
					s.address,
					ST_Distance(s.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS distance_meters
				FROM store s
				WHERE s.region_2nd LIKE CONCAT('%', :r2, '%')
				AND s.region_1st LIKE CONCAT('%', :r1, '%')
				AND s.status = 'ACTIVE'
				ORDER BY distance_meters ASC
			""", nativeQuery = true)
	Slice<RegionByDistanceProjection> findStoresByRegion2ndOrderByDistance(
			@Param("r2") String r2,
			@Param("r1") String r1,
			@Param("lng") double longitude,
			@Param("lat") double latitude,
			Pageable pageable);

	@Query("""
				SELECT s.id AS id, s.name AS name, s.address AS address, COUNT(ss.id) AS subscribeCount
				FROM Store s
				LEFT JOIN SubscribedStore ss ON ss.store = s
				WHERE s.region2nd LIKE CONCAT('%', :r2, '%')
				AND s.region1st LIKE CONCAT('%', :r1, '%')
				AND s.status = 'ACTIVE'
				GROUP BY s.id, s.name, s.address
				ORDER BY COUNT(ss.id) DESC
			""")
	Slice<RegionBySubscribeProjection> findStoresByRegion2ndOrderBySubscribeCount(
			@Param("r2") String r2,
			@Param("r1") String r1,
			Pageable pageable);

	// ── region_1st (시/도) 기준 ───────────────────────────────────────────────

	@Query(value = """
				SELECT
					s.id,
					s.name,
					s.address,
					ST_Distance(s.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS distance_meters
				FROM store s
				WHERE s.region_1st LIKE CONCAT('%', :r1, '%')
				AND s.status = 'ACTIVE'
				ORDER BY distance_meters ASC
			""", nativeQuery = true)
	Slice<RegionByDistanceProjection> findStoresByRegion1stOrderByDistance(
			@Param("r1") String r1,
			@Param("lng") double longitude,
			@Param("lat") double latitude,
			Pageable pageable);

	@Query("""
				SELECT s.id AS id, s.name AS name, s.address AS address, COUNT(ss.id) AS subscribeCount
				FROM Store s
				LEFT JOIN SubscribedStore ss ON ss.store = s
				WHERE s.region1st LIKE CONCAT('%', :r1, '%')
				AND s.status = 'ACTIVE'
				GROUP BY s.id, s.name, s.address
				ORDER BY COUNT(ss.id) DESC
			""")
	Slice<RegionBySubscribeProjection> findStoresByRegion1stOrderBySubscribeCount(
			@Param("r1") String r1,
			Pageable pageable);

	// ─────────────────────────────────────────────────────────────────────────

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			INSERT INTO store (
					name, address, region_1st, region_2nd, region_3rd,
					location, phone, kakao_place_id, source, status, created_at, updated_at)
			VALUES (:name, :address, :region1st, :region2nd, :region3rd,
					ST_SetSRID(ST_MakePoint(:x, :y), 4326)::geography, :phone, :kakaoPlaceId,
					'KAKAO_IMPORTED', 'ACTIVE', NOW(), NOW())
			ON CONFLICT (kakao_place_id) DO NOTHING
			""", nativeQuery = true)
	void insertIfNotExistsByKakaoPlaceId(
			@Param("name") String name,
			@Param("address") String address,
			@Param("region1st") String region1st,
			@Param("region2nd") String region2nd,
			@Param("region3rd") String region3rd,
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
					s.region_1st,
					s.region_2nd,
					ST_Distance(s.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS distance_meters
				FROM store s
				WHERE ST_DWithin(s.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radius)
				AND s.status = 'ACTIVE'
				ORDER BY distance_meters ASC
			""", nativeQuery = true)
	Slice<NearbyStoreProjection> findNearbyStores(@Param("lng") double longitude, @Param("lat") double latitude,
			@Param("radius") double radiusMeters, Pageable pageable);

	@Query(value = """
				SELECT
					s.id,
					s.name,
					s.region_1st,
					s.region_2nd,
					COUNT(ss.id) AS subscribe_count,
					ST_Distance(s.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS distance_meters
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

	List<Store> findByKakaoPlaceIdIsNotNull();

	List<Store> findByKakaoPlaceIdIn(List<String> kakaoPlaceIds);

	Optional<Store> findByKakaoPlaceId(String kakaoPlaceId);

	@Modifying
	@Query("UPDATE Store s SET s.region1st = :r1, s.region2nd = :r2, s.region3rd = :r3 WHERE s.id = :id")
	void updateRegionById(@Param("id") Long id,
			@Param("r1") String r1, @Param("r2") String r2, @Param("r3") String r3);
}

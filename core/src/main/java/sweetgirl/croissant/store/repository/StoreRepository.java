package sweetgirl.croissant.store.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import sweetgirl.croissant.store.dto.projection.NearbyStoreProjection;
import sweetgirl.croissant.store.dto.projection.NextStoreCandidateProjection;
import sweetgirl.croissant.store.dto.projection.RegionByDistanceProjection;
import sweetgirl.croissant.store.dto.projection.RegionBySubscribeProjection;
import sweetgirl.croissant.store.dto.projection.StoreDistanceProjection;
import sweetgirl.croissant.store.dto.projection.StoreInBoundsProjection;
import sweetgirl.croissant.store.dto.projection.TopSubscribedStoreProjection;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.enums.StoreStatus;

public interface StoreRepository extends JpaRepository<Store, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT store FROM Store store WHERE store.id = :storeId")
	Optional<Store> findByIdForUpdate(@Param("storeId") Long storeId);

	// 트렌드 홈 거리 표시용 — 가게들의 사용자 위치 기준 거리를 일괄 조회 (N+1 방지)
	@Query(value = """
				SELECT
					s.id,
					ST_Distance(s.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS distance_meters
				FROM store s
				WHERE s.id IN (:storeIds)
			""", nativeQuery = true)
	List<StoreDistanceProjection> findDistancesByStoreIdIn(
			@Param("storeIds") List<Long> storeIds,
			@Param("lng") double longitude,
			@Param("lat") double latitude);

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

	// 카카오로 들어와 있던 가게에 네이버 장소만 이어 붙인다(이미 있으면 그대로 둔다).
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			UPDATE store
			SET naver_place_id = :naverPlaceId, updated_at = NOW()
			WHERE id = :storeId AND naver_place_id IS NULL
			""", nativeQuery = true)
	void attachNaverPlaceId(@Param("storeId") Long storeId, @Param("naverPlaceId") String naverPlaceId);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			INSERT INTO store (
					name, address, region_1st, region_2nd, region_3rd,
					location, phone, kakao_place_id, source, status, category,
					created_at, updated_at)
			VALUES (:name, :address, :region1st, :region2nd, :region3rd,
					ST_SetSRID(ST_MakePoint(:x, :y), 4326)::geography, :phone, :kakaoPlaceId,
					'KAKAO_IMPORTED', 'ACTIVE', :category, NOW(), NOW())
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
			@Param("kakaoPlaceId") String kakaoPlaceId,
			@Param("category") String category);

	@Query(value = """
				SELECT
					s.id,
					s.name,
					s.region_1st AS region1st,
					s.region_2nd AS region2nd,
					ST_Y(s.location::geometry) AS latitude,
					ST_X(s.location::geometry) AS longitude,
					s.category,
					ST_Distance(s.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS distance_meters
				FROM store s
				WHERE ST_DWithin(s.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radius)
				AND s.status = 'ACTIVE'
				ORDER BY distance_meters ASC
			""", nativeQuery = true)
	Slice<NearbyStoreProjection> findNearbyStores(@Param("lng") double longitude, @Param("lat") double latitude,
			@Param("radius") double radiusMeters, Pageable pageable);

	/**
	 * 다음 빵집 제안 — 근처 {@code radius} m 안 활성 가게 중 내가 아직 기록하지 않은 곳. 공개 빵로그 많은 순, 가까운 순.
	 * 방금 기록한 가게({@code excludeStoreId}, 없으면 -1)는 뺀다.
	 */
	@Query(value = """
				SELECT s.id AS id, s.name AS name, s.address AS address,
					ST_Y(s.location::geometry) AS latitude, ST_X(s.location::geometry) AS longitude,
					ST_Distance(s.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS distanceMeters,
					(SELECT COUNT(*) FROM bread_record r WHERE r.store_id = s.id AND r.is_public = true
						AND r.deleted_at IS NULL AND r.hidden_at IS NULL) AS publicRecordCount
				FROM store s
				WHERE ST_DWithin(s.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radius)
					AND s.status = 'ACTIVE' AND s.id <> :excludeStoreId
					AND NOT EXISTS (SELECT 1 FROM bread_record mine
						WHERE mine.member_id = :memberId AND mine.store_id = s.id AND mine.deleted_at IS NULL)
				ORDER BY publicRecordCount DESC, distanceMeters ASC
				LIMIT :limit
			""", nativeQuery = true)
	List<NextStoreCandidateProjection> findNearbyUnrecordedPopular(@Param("memberId") Long memberId,
			@Param("excludeStoreId") Long excludeStoreId, @Param("lng") double longitude, @Param("lat") double latitude,
			@Param("radius") double radiusMeters, @Param("limit") int limit);

	// 구독(빵알람)은 더 쓰지 않는 기능이라 인기도(popularity_score) 순으로 뽑는다.
	// idx_store_popularity_score 를 순서대로 읽다가 limit 에서 멈춘다 — 정렬 없음(운영 EXPLAIN 2026-09-28).
	// 지역·주소는 1.5.x 앱이 가게 고르기 화면에서 그대로 읽는다 — 빼면 앱이 꺼진다(2026-10-02, #818 회귀).
	@Query(value = """
				SELECT s.id, s.name, s.region_1st AS region1st, s.region_2nd AS region2nd, s.address
				FROM store s
				WHERE s.status = 'ACTIVE'
				ORDER BY s.popularity_score DESC
				LIMIT :limit
			""", nativeQuery = true)
	List<TopSubscribedStoreProjection> findTopSubscribedStores(@Param("limit") int limit);

	boolean existsById(Long storeId);

	List<Store> findByKakaoPlaceIdIsNotNull();

	List<Store> findByKakaoPlaceIdIn(List<String> kakaoPlaceIds);

	Optional<Store> findByKakaoPlaceId(String kakaoPlaceId);

	@Query(value = """
			SELECT * FROM store
			WHERE status = 'ACTIVE'
			AND REPLACE(LOWER(name), ' ', '') = :compactName
			ORDER BY id ASC
			""", nativeQuery = true)
	List<Store> findActiveStoresByCompactName(@Param("compactName") String compactName);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			UPDATE store
			SET kakao_place_id = :kakaoPlaceId,
				phone = CASE
					WHEN :phone IS NULL OR :phone = '' THEN phone
					ELSE :phone
				END,
				location = ST_SetSRID(ST_MakePoint(:x, :y), 4326)::geography,
				updated_at = NOW()
			WHERE id = :id
			""", nativeQuery = true)
	void updateKakaoPlaceMetadata(
			@Param("id") Long id,
			@Param("kakaoPlaceId") String kakaoPlaceId,
			@Param("phone") String phone,
			@Param("x") double longitude,
			@Param("y") double latitude);

	@Modifying
	@Query("UPDATE Store s SET s.region1st = :r1, s.region2nd = :r2, s.region3rd = :r3 WHERE s.id = :id")
	void updateRegionById(@Param("id") Long id,
			@Param("r1") String r1, @Param("r2") String r2, @Param("r3") String r3);

	// ── Naver enrich 큐 ─────────────────────────────────────────────────────
	// 아직 네이버 플레이스 ID 가 매칭되지 않은 ACTIVE Store.
	//   - 미시도 (naverMatchedAt IS NULL) — 큐 진입
	//   - 매칭 실패 후 retryThreshold 시점 경과 — 재시도 큐 진입
	// 메뉴 유무는 큐 조건에서 제외. naverPlaceId 가 없으면 메뉴가 이미 있어도 ID 만 채우러 들어옴.
	// 매칭 성공 시 markNaverMatched 가 naverPlaceId 와 naverMatchedAt 을 모두 set 해서 자동 제외됨.
	// 파킹: naverEnrichAttempts >= maxAttempts 면 '진짜 못 찾는 가게'로 보고 큐에서 제외(무한 재시도 방지).
	@Query("""
				SELECT s FROM Store s
				WHERE s.status = sweetgirl.croissant.store.enums.StoreStatus.ACTIVE
				AND s.naverPlaceId IS NULL
				AND s.naverEnrichAttempts < :maxAttempts
				AND (s.naverMatchedAt IS NULL OR s.naverMatchedAt < :retryThreshold)
				AND (
					:partition IS NULL
					OR (:partition = 'P1' AND MOD(s.id, 3) = 0)
					OR (:partition = 'P2' AND MOD(s.id, 3) = 1)
					OR (:partition = 'P3' AND MOD(s.id, 3) = 2)
				)
				ORDER BY s.id ASC
			""")
	Page<Store> findNaverEnrichQueue(
			@Param("retryThreshold") Instant retryThreshold,
			@Param("maxAttempts") int maxAttempts,
			@Param("partition") String partition,
			Pageable pageable);

	// 매칭은 됐지만 가게 사진이 0장인 가게 — 사진 보충 일괄 크롤 대상.
	// 이미지 테이블을 left join 해서 NULL/empty 만 필터.
	@Query("""
				SELECT s FROM Store s
				WHERE s.status = sweetgirl.croissant.store.enums.StoreStatus.ACTIVE
				AND s.naverPlaceId IS NOT NULL
				AND (:excludedIdsEmpty = true OR s.id NOT IN :excludedIds)
				AND NOT EXISTS (
					SELECT 1 FROM StoreImageFile i WHERE i.store.id = s.id
				)
				AND (
					:partition IS NULL
					OR (:partition = 'P1' AND MOD(s.id, 3) = 0)
					OR (:partition = 'P2' AND MOD(s.id, 3) = 1)
					OR (:partition = 'P3' AND MOD(s.id, 3) = 2)
				)
				ORDER BY s.id ASC
			""")
	Page<Store> findMatchedWithoutPhotos(
			@Param("excludedIds") Set<Long> excludedIds,
			@Param("excludedIdsEmpty") boolean excludedIdsEmpty,
			@Param("partition") String partition,
			Pageable pageable);

	@Query("""
				SELECT COUNT(s) FROM Store s
				WHERE s.status = sweetgirl.croissant.store.enums.StoreStatus.ACTIVE
				AND s.naverPlaceId IS NOT NULL
				AND (:excludedIdsEmpty = true OR s.id NOT IN :excludedIds)
				AND NOT EXISTS (
					SELECT 1 FROM StoreImageFile i WHERE i.store.id = s.id
				)
				AND (
					:partition IS NULL
					OR (:partition = 'P1' AND MOD(s.id, 3) = 0)
					OR (:partition = 'P2' AND MOD(s.id, 3) = 1)
					OR (:partition = 'P3' AND MOD(s.id, 3) = 2)
				)
			""")
	long countMatchedWithoutPhotos(
			@Param("excludedIds") Set<Long> excludedIds,
			@Param("excludedIdsEmpty") boolean excludedIdsEmpty,
			@Param("partition") String partition);

	// 매칭됐지만(naverPlaceId 있음) 활성 메뉴, 가게 사진, 또는 메뉴 사진이 비어 enrich 가 안 끝난 가게 — 풀 enrich 백필 대상.
	// 수동/후보 매칭으로 placeId 만 채워진 가게(메뉴·사진 0)가 자동으로 여기 들어온다. (사진만 보충하는
	// findMatchedWithoutPhotos 와 달리 메뉴 누락과 메뉴별 사진 누락도 잡고, 크롤러가 메뉴+사진 둘 다 채운다.)
	@Query("""
				SELECT s FROM Store s
				WHERE s.status = sweetgirl.croissant.store.enums.StoreStatus.ACTIVE
				AND s.naverPlaceId IS NOT NULL
				AND (:excludedIdsEmpty = true OR s.id NOT IN :excludedIds)
				AND (
					NOT EXISTS (SELECT 1 FROM Menu m WHERE m.store.id = s.id AND m.deletedAt IS NULL)
					OR NOT EXISTS (SELECT 1 FROM StoreImageFile i WHERE i.store.id = s.id)
					OR EXISTS (
						SELECT 1 FROM Menu m
						WHERE m.store.id = s.id
						AND m.deletedAt IS NULL
						AND NOT EXISTS (
							SELECT 1 FROM MenuImageFile mi WHERE mi.menu.id = m.id
						)
					)
				)
				AND (
					:partition IS NULL
					OR (:partition = 'P1' AND MOD(s.id, 3) = 0)
					OR (:partition = 'P2' AND MOD(s.id, 3) = 1)
					OR (:partition = 'P3' AND MOD(s.id, 3) = 2)
				)
				ORDER BY s.id ASC
			""")
	Page<Store> findMatchedNeedingEnrich(
			@Param("excludedIds") Set<Long> excludedIds,
			@Param("excludedIdsEmpty") boolean excludedIdsEmpty,
			@Param("partition") String partition,
			Pageable pageable);

	@Query("""
				SELECT COUNT(s) FROM Store s
				WHERE s.status = sweetgirl.croissant.store.enums.StoreStatus.ACTIVE
				AND s.naverPlaceId IS NOT NULL
				AND (:excludedIdsEmpty = true OR s.id NOT IN :excludedIds)
				AND (
					NOT EXISTS (SELECT 1 FROM Menu m WHERE m.store.id = s.id AND m.deletedAt IS NULL)
					OR NOT EXISTS (SELECT 1 FROM StoreImageFile i WHERE i.store.id = s.id)
					OR EXISTS (
						SELECT 1 FROM Menu m
						WHERE m.store.id = s.id
						AND m.deletedAt IS NULL
						AND NOT EXISTS (
							SELECT 1 FROM MenuImageFile mi WHERE mi.menu.id = m.id
						)
					)
				)
				AND (
					:partition IS NULL
					OR (:partition = 'P1' AND MOD(s.id, 3) = 0)
					OR (:partition = 'P2' AND MOD(s.id, 3) = 1)
					OR (:partition = 'P3' AND MOD(s.id, 3) = 2)
				)
			""")
	long countMatchedNeedingEnrich(
			@Param("excludedIds") Set<Long> excludedIds,
			@Param("excludedIdsEmpty") boolean excludedIdsEmpty,
			@Param("partition") String partition);

	Optional<Store> findByNaverPlaceId(String naverPlaceId);

	/**
	 * 좌표 반경 안의 가게를 상태 무관하게 가까운 순으로 — 실패 검색어 판정이 네이버 장소와 우리 가게를 대조할 때 쓴다.
	 *
	 * <p>{@code findNearbyStores}와 달리 DELETED 도 돌려준다. 폐업으로 내린 가게를 "없음"으로 보면 다시 등록하게 된다.
	 */
	@Query(value = """
				SELECT s.*
				FROM store s
				WHERE ST_DWithin(s.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radius)
				ORDER BY ST_Distance(s.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography)
				LIMIT 20
			""", nativeQuery = true)
	List<Store> findAllWithinRadius(@Param("lng") double longitude, @Param("lat") double latitude,
			@Param("radius") double radiusMeters);

	// naver_matched_at은 place id가 있을 때만 찍는다 — id 없이 NOW()를 찍으면
	// 네이버 보강 큐(id NULL & matched_at NULL 대상)에서 영영 빠져 사진이 안 채워진다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			INSERT INTO store (
					name, address, region_1st, region_2nd, region_3rd,
					location, phone, naver_place_id, naver_matched_at, naver_enrich_attempts,
					source, status, category, created_at, updated_at)
			VALUES (:name, :address, :region1st, :region2nd, :region3rd,
					ST_SetSRID(ST_MakePoint(:x, :y), 4326)::geography, :phone, :naverPlaceId,
					CASE WHEN CAST(:naverPlaceId AS text) IS NULL THEN NULL ELSE NOW() END,
					0, 'ADMIN_REGISTERED', 'ACTIVE', :category, NOW(), NOW())
			ON CONFLICT (naver_place_id) DO NOTHING
			""", nativeQuery = true)
	void insertIfNotExistsByNaverPlaceId(
			@Param("name") String name,
			@Param("address") String address,
			@Param("region1st") String region1st,
			@Param("region2nd") String region2nd,
			@Param("region3rd") String region3rd,
			@Param("x") double longitude,
			@Param("y") double latitude,
			@Param("phone") String phone,
			@Param("naverPlaceId") String naverPlaceId,
			@Param("category") String category);

	// ── Naver enrich stats ──────────────────────────────────────────────────
	@Query("""
				SELECT COUNT(s) FROM Store s
				WHERE s.status = sweetgirl.croissant.store.enums.StoreStatus.ACTIVE
				AND s.naverPlaceId IS NOT NULL
			""")
	long countNaverMatched();

	@Query("""
				SELECT COUNT(s) FROM Store s
				WHERE s.status = sweetgirl.croissant.store.enums.StoreStatus.ACTIVE
				AND s.naverPlaceId IS NULL
				AND s.naverMatchedAt IS NULL
			""")
	long countNaverPending();

	@Query("""
				SELECT COUNT(s) FROM Store s
				WHERE s.status = sweetgirl.croissant.store.enums.StoreStatus.ACTIVE
				AND s.naverPlaceId IS NULL
				AND s.naverMatchedAt IS NOT NULL
			""")
	long countNaverFailed();

	@Query("""
				SELECT COUNT(s) FROM Store s
				WHERE s.status = sweetgirl.croissant.store.enums.StoreStatus.ACTIVE
			""")
	long countActiveTotal();

	// 매칭 실패 후 7일 throttle 진행 중인 가게 — 어드민에서 "왜 실패했나" 보거나 즉시 재시도 트리거.
	// 큐(findNaverEnrichQueue)는 throttle 만료된 것만 보여주므로 분리.
	@Query("""
				SELECT s FROM Store s
				WHERE s.status = sweetgirl.croissant.store.enums.StoreStatus.ACTIVE
				AND s.naverPlaceId IS NULL
				AND s.naverMatchedAt IS NOT NULL
				AND s.naverMatchedAt >= :throttleAliveSince
				ORDER BY s.naverMatchedAt DESC
			""")
	Page<Store> findNaverThrottled(@Param("throttleAliveSince") Instant throttleAliveSince, Pageable pageable);

	@Query(value = """
				SELECT s.region_1st, s.region_2nd, s.region_3rd,
					ST_Distance(s.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS distance_meters
				FROM store s
				WHERE s.region_3rd IS NOT NULL
				AND s.status = 'ACTIVE'
				ORDER BY distance_meters ASC
				LIMIT 1
			""", nativeQuery = true)
	List<Object[]> findNearestRegion(
			@Param("lng") double longitude,
			@Param("lat") double latitude);

	/**
	 * 지도 영역(사각형) 안 가게를 기준점에서 가까운 순으로 limit 곳.
	 *
	 * <p>{@code &&}(영역 상자 겹침)로 store.location 의 GIST 인덱스를 태워 후보를 좁히고, 위경도 BETWEEN 으로
	 * 앱이 보는 사각형과 정확히 맞춘다 — geography 상자는 구면이라 모서리가 조금 넉넉하다.
	 *
	 * <p>영역 안 가게 수는 {@link #countStoresInBounds} 로 따로 센다. 여기서 {@code COUNT(*) OVER ()} 를 같이 내면
	 * 영역 안 줄을 다 만든 뒤에야 LIMIT 을 걸 수 있어 {@code <->} 의 가까운 순 탐색(KNN)이 무너진다.
	 */
	@Query(value = """
			SELECT
			s.id AS "storeId"
			FROM store s
			WHERE s.status = 'ACTIVE'
			AND s.location && ST_MakeEnvelope(:swLng, :swLat, :neLng, :neLat, 4326)::geography
			AND ST_Y(s.location::geometry) BETWEEN :swLat AND :neLat
			AND ST_X(s.location::geometry) BETWEEN :swLng AND :neLng
			ORDER BY s.location <-> ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, s.id
			LIMIT :limit
			""", nativeQuery = true)
	List<StoreInBoundsProjection> findStoresInBounds(
			@Param("swLat") double swLat,
			@Param("swLng") double swLng,
			@Param("neLat") double neLat,
			@Param("neLng") double neLng,
			@Param("lat") double latitude,
			@Param("lng") double longitude,
			@Param("limit") int limit);

	/**
	 * 영역 안 ACTIVE 가게 수, 단 {@code cap} 곳까지만 센다. 전국을 띄운 지도에서도 세는 비용이 cap 을 넘지 않게.
	 * 조건은 {@link #findStoresInBounds} 와 같다.
	 */
	@Query(value = """
			SELECT COUNT(*) FROM (
			SELECT 1
			FROM store s
			WHERE s.status = 'ACTIVE'
			AND s.location && ST_MakeEnvelope(:swLng, :swLat, :neLng, :neLat, 4326)::geography
			AND ST_Y(s.location::geometry) BETWEEN :swLat AND :neLat
			AND ST_X(s.location::geometry) BETWEEN :swLng AND :neLng
			LIMIT :cap
			) bounded
			""", nativeQuery = true)
	long countStoresInBounds(
			@Param("swLat") double swLat,
			@Param("swLng") double swLng,
			@Param("neLat") double neLat,
			@Param("neLng") double neLng,
			@Param("cap") int cap);
}

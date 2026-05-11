package sweetgirl.croissant.store.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.common.enums.StoreStatus;
import sweetgirl.croissant.store.entity.Store;

public interface StoreRepository extends JpaRepository<Store, Long> {

	Slice<Store> findByNameContainingAndStatus(String name, StoreStatus status, Pageable pageable);

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
}

package sweetgirl.croissant.store.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.dto.projection.GeofenceCandidateProjection;
import sweetgirl.croissant.store.entity.SubscribedStore;

public interface SubscribedStoreRepository extends JpaRepository<SubscribedStore, Long> {

	long countByMember(Member member);

	Slice<SubscribedStore> findAllByMemberOrderByCreatedAtDesc(Member member, Pageable pageable);

	boolean existsByMemberIdAndStoreId(Long memberId, Long storeId);

	Optional<SubscribedStore> findByMemberIdAndStoreId(Long memberId, Long storeId);

	// 현재 위치 기준 반경 내 구독 가게 후보 최대 20개 반환 (OS Geofence 등록용)
	@Query(value = """
			SELECT
			s.id AS "storeId",
			s.name AS "storeName",
			ST_Y(s.location::geometry) AS "latitude",
			ST_X(s.location::geometry) AS "longitude",
			ST_Distance(s.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS "distanceMeters"
			FROM subscribed_store ss
			JOIN store s ON ss.store_id = s.id
			WHERE ss.member_id = :memberId
			AND s.status = 'ACTIVE'
			AND ST_DWithin(s.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :candidateRadius)
			ORDER BY "distanceMeters" ASC
			LIMIT 20
			""", nativeQuery = true)
	List<GeofenceCandidateProjection> findGeofenceCandidates(
			@Param("memberId") Long memberId,
			@Param("lat") double lat,
			@Param("lng") double lng,
			@Param("candidateRadius") double candidateRadius);

	// 해당 storeId가 사용자의 구독 가게이면서 요청 좌표가 반경 안인지 검증
	@Query(value = """
			SELECT EXISTS (
			SELECT 1
			FROM subscribed_store ss
			JOIN store s ON ss.store_id = s.id
			WHERE ss.member_id = :memberId
			AND s.id = :storeId
			AND s.status = 'ACTIVE'
			AND ST_DWithin(s.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radius)
			)
			""", nativeQuery = true)
	boolean existsSubscribedStoreWithinRadius(
			@Param("memberId") Long memberId,
			@Param("storeId") Long storeId,
			@Param("lat") double lat,
			@Param("lng") double lng,
			@Param("radius") double radius);
}

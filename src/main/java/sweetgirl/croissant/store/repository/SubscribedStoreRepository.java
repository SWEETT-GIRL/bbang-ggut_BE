package sweetgirl.croissant.store.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.entity.SubscribedStore;

public interface SubscribedStoreRepository extends JpaRepository<SubscribedStore, Long> {

	long countByMember(Member member);

	Slice<SubscribedStore> findAllByMemberOrderByCreatedAtDesc(Member member, Pageable pageable);

	// 멤버의 구독 가게 중 현재 위치 기준 반경 내 가게 조회
	// ::geography 캐스팅 필수 — 없으면 단위가 도(degree)로 계산되어 반경이 잘못 적용됨
	@Query(value = """
			SELECT ss.* FROM subscribed_store ss
			JOIN store s ON ss.store_id = s.id
			WHERE ss.member_id = :memberId
			AND ST_DWithin(s.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radius)
			""", nativeQuery = true)
	List<SubscribedStore> findNearbySubscribedStores(
			@Param("memberId") Long memberId,
			@Param("lat") Double latitude,
			@Param("lng") Double longitude,
			@Param("radius") double radius);

}

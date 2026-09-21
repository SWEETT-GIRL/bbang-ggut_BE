package sweetgirl.croissant.world.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.world.entity.MemberPlanetArrival;
import sweetgirl.croissant.world.entity.MemberPlanetArrivalId;

public interface MemberPlanetArrivalRepository
		extends JpaRepository<MemberPlanetArrival, MemberPlanetArrivalId> {

	List<MemberPlanetArrival> findAllByIdMemberId(Long memberId);

	/**
	 * 룰렛을 돌린 것으로 표시한다. <b>이중 지급을 막는 유일한 장치다.</b>
	 *
	 * <p>읽고-판단하고-쓰기로 하면 두 요청이 나란히 "아직 안 돌렸다"를 보고 둘 다 친구를 준다.
	 * {@code roulette_at IS NULL} 을 조건에 넣은 단일 UPDATE 라 한쪽만 1 을 받고, 0 을 받은 쪽은
	 * 이미 돌린 것으로 본다.
	 */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			UPDATE member_planet_arrivals
			SET roulette_at = CURRENT_TIMESTAMP
			WHERE member_id = :memberId AND planet_code = :planetCode AND roulette_at IS NULL
			""", nativeQuery = true)
	int markRouletteSpun(@Param("memberId") Long memberId, @Param("planetCode") String planetCode);

	/** 처음 닿은 때를 남긴다. 이미 있으면 그때를 덮어쓰지 않는다. */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			INSERT INTO member_planet_arrivals (member_id, planet_code, arrived_at)
			VALUES (:memberId, :planetCode, CURRENT_TIMESTAMP)
			ON CONFLICT (member_id, planet_code) DO NOTHING
			""", nativeQuery = true)
	int arriveIfAbsent(@Param("memberId") Long memberId, @Param("planetCode") String planetCode);
}

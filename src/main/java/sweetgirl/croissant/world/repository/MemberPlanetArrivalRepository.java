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

	/** 처음 닿은 때를 남긴다. 이미 있으면 그때를 덮어쓰지 않는다. */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			INSERT INTO member_planet_arrivals (member_id, planet_code, arrived_at)
			VALUES (:memberId, :planetCode, CURRENT_TIMESTAMP)
			ON CONFLICT (member_id, planet_code) DO NOTHING
			""", nativeQuery = true)
	int arriveIfAbsent(@Param("memberId") Long memberId, @Param("planetCode") String planetCode);
}

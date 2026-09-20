package sweetgirl.croissant.world.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.world.entity.MemberPlanetArrival;
import sweetgirl.croissant.world.entity.MemberPlanetArrivalId;

public interface MemberPlanetArrivalRepository
		extends JpaRepository<MemberPlanetArrival, MemberPlanetArrivalId> {

	List<MemberPlanetArrival> findAllByIdMemberId(Long memberId);
}

package sweetgirl.croissant.mission.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.mission.entity.Mission;

public interface MissionRepository extends JpaRepository<Mission, Long> {

	List<Mission> findAllByActiveTrueOrderBySortOrderAscIdAsc();

	List<Mission> findAllByOrderBySortOrderAscIdAsc();

	boolean existsByCode(String code);
}

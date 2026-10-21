package sweetgirl.croissant.menu.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.menu.entity.FamousMenuByRegion;
import sweetgirl.croissant.store.enums.Region3rd;

public interface FamousMenuByRegionRepository extends JpaRepository<FamousMenuByRegion, Long> {

	List<FamousMenuByRegion> findByRegion3rd(Region3rd region3rd);
}

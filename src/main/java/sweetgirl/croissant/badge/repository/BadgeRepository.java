package sweetgirl.croissant.badge.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.badge.entity.Badge;

public interface BadgeRepository extends JpaRepository<Badge, Long> {

	List<Badge> findAllByActiveTrueOrderBySortOrderAscIdAsc();

	List<Badge> findAllByOrderBySortOrderAscIdAsc();

	Optional<Badge> findByCode(String code);

	boolean existsByCode(String code);
}

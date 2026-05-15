package sweetgirl.croissant.menu.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.menu.entity.RecommendMenu;

public interface RecommendMenuRepository extends JpaRepository<RecommendMenu, Long> {

	boolean existsByMemberIdAndMenuId(Long memberId, Long menuId);

	Optional<RecommendMenu> findByMemberIdAndMenuId(Long memberId, Long menuId);

	List<RecommendMenu> findByMemberIdAndMenuIdIn(Long memberId, List<Long> menuIds);
}

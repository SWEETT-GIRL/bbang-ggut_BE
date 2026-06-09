package sweetgirl.croissant.menu.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.menu.entity.MenuLike;

public interface MenuLikeRepository extends JpaRepository<MenuLike, Long> {

	boolean existsByMemberIdAndMenuId(Long memberId, Long menuId);

	Optional<MenuLike> findByMemberIdAndMenuId(Long memberId, Long menuId);

	List<MenuLike> findByMemberIdAndMenuIdIn(Long memberId, List<Long> menuIds);
}

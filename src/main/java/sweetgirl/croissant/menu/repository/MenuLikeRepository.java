package sweetgirl.croissant.menu.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.menu.entity.MenuLike;

public interface MenuLikeRepository extends JpaRepository<MenuLike, Long> {

	boolean existsByMemberIdAndMenuId(Long memberId, Long menuId);

	Optional<MenuLike> findByMemberIdAndMenuId(Long memberId, Long menuId);

	List<MenuLike> findByMemberIdAndMenuIdIn(Long memberId, List<Long> menuIds);

	// ── 게스트 → 회원 데이터 이관 ─────────────────────────────
	@Modifying(clearAutomatically = true)
	@Query("DELETE FROM MenuLike m WHERE m.member.id = :guestId AND EXISTS "
		+ "(SELECT 1 FROM MenuLike t WHERE t.member.id = :targetId AND t.menu.id = m.menu.id)")
	int deleteGuestDuplicates(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

	@Modifying(clearAutomatically = true)
	@Query("UPDATE MenuLike m SET m.member.id = :targetId WHERE m.member.id = :guestId")
	int moveOwner(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

}

package sweetgirl.croissant.member.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import sweetgirl.croissant.member.entity.LikedMenuCategory;
import sweetgirl.croissant.menu.entity.MenuCategory;

@Repository
public interface LikedMenuCategoryRepository extends JpaRepository<LikedMenuCategory, Long> {

	@Query("SELECT lm.menuCategory FROM LikedMenuCategory lm WHERE lm.member.id = :memberId")
	List<MenuCategory> findMenuCategoriesByMemberId(@Param("memberId") Long memberId);

	@Modifying
	@Query("DELETE FROM LikedMenuCategory lm WHERE lm.member.id = :memberId")
	void deleteByMemberId(@Param("memberId") Long memberId);

	// ── 게스트 → 회원 데이터 이관 ─────────────────────────────
	@Modifying(clearAutomatically = true)
	@Query("DELETE FROM LikedMenuCategory l WHERE l.member.id = :guestId AND EXISTS "
		+ "(SELECT 1 FROM LikedMenuCategory t WHERE t.member.id = :targetId "
		+ "AND t.menuCategory.id = l.menuCategory.id)")
	int deleteGuestDuplicates(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

	@Modifying(clearAutomatically = true)
	@Query("UPDATE LikedMenuCategory l SET l.member.id = :targetId WHERE l.member.id = :guestId")
	int moveOwner(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

}

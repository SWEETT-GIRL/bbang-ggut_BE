package sweetgirl.croissant.member.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import sweetgirl.croissant.member.entity.LikedMenuCategory;
import sweetgirl.croissant.menu.entity.MenuCategory;

@Repository
public interface LikedMenuCategoryRepository extends JpaRepository<LikedMenuCategory, Long> {

	@Query("SELECT lm.menuCategory FROM LikedMenuCategory lm WHERE lm.member.id = :memberId")
	List<MenuCategory> findMenuCategoriesByMemberId(@Param("memberId") Long memberId);

	void deleteByMemberId(Long memberId);
}

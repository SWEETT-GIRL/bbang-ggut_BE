package sweetgirl.croissant.breadranking.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.breadranking.entity.MemberBreadCategory;
import sweetgirl.croissant.breadranking.entity.MemberBreadCategoryId;

public interface MemberBreadCategoryRepository extends JpaRepository<MemberBreadCategory, MemberBreadCategoryId> {

	/** 내 탭 — 카테고리까지 한 번에, 순서대로. */
	@Query("""
			SELECT tab
			FROM MemberBreadCategory tab
			JOIN FETCH tab.category
			WHERE tab.id.memberId = :memberId
			ORDER BY tab.sortOrder ASC, tab.id.categoryId ASC
			""")
	List<MemberBreadCategory> findAllWithCategoryByMemberId(@Param("memberId") Long memberId);
}

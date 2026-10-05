package sweetgirl.croissant.breadranking.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

	/**
	 * 탭 맨 뒤에 붙인다 — 이미 있으면 그대로(0). 순서(지금 탭 수)도 같은 문장 안에서 세어 더블탭에 겹치지 않는다.
	 *
	 * @return 넣었으면 1, 이미 있어 안 넣었으면 0
	 */
	@Modifying
	@Query(value = """
			INSERT INTO member_bread_category (member_id, category_id, sort_order, created_at)
			VALUES (:memberId, :categoryId,
				(SELECT COUNT(*) FROM member_bread_category t WHERE t.member_id = :memberId), NOW())
			ON CONFLICT (member_id, category_id) DO NOTHING
			""", nativeQuery = true)
	int appendIfAbsent(@Param("memberId") Long memberId, @Param("categoryId") Long categoryId);
}

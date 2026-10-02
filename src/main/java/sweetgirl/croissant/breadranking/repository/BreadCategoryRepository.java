package sweetgirl.croissant.breadranking.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.breadranking.dto.projection.BreadCategoryVoters;
import sweetgirl.croissant.breadranking.entity.BreadCategory;
import sweetgirl.croissant.menu.enums.MenuKind;

public interface BreadCategoryRepository extends JpaRepository<BreadCategory, Long> {

	Optional<BreadCategory> findByNameKey(String nameKey);

	Optional<BreadCategory> findByMenuKind(MenuKind menuKind);

	/** 기본 목록이 덮는 MenuKind 전부 — 기동 때 빠진 종류를 채우려고. */
	@Query("SELECT c.menuKind FROM BreadCategory c WHERE c.menuKind IS NOT NULL")
	List<MenuKind> findAllMenuKinds();

	/**
	 * 사용자 생성 종류를 같은 이름(name_key)이 없을 때만 넣는다 — 같은 이름을 동시에 만들어도 한 쪽만 들어가고 다른 쪽은 0 을 받아
	 * 기존 것을 읽는다(유니크 위반 500 대신). 감사 컬럼은 SQL 이 채운다.
	 *
	 * @return 넣었으면 1, 이미 있어 안 넣었으면 0
	 */
	@Modifying
	@Query(value = """
			INSERT INTO bread_category (name, name_key, bread_group, created_by, created_at)
			VALUES (:name, :nameKey, :breadGroup, :createdBy, NOW())
			ON CONFLICT (name_key) DO NOTHING
			""", nativeQuery = true)
	int insertCustomIfAbsent(
			@Param("name") String name,
			@Param("nameKey") String nameKey,
			@Param("breadGroup") String breadGroup,
			@Param("createdBy") Long createdBy);

	/**
	 * 공용 목록 — 기본 종류 + 내가 만든 것 + 서로 다른 minTabMembers 명 이상이 자기 탭에 넣은 사용자 생성 종류(#843).
	 * 한 사람이 만든 이름(자판 실수·욕설)이 바로 전체에 퍼지지 않게 한다.
	 *
	 * <p>순서: 고정 자리(display_order, 기본 8종) → 1등을 매긴 서로 다른 회원 수(탈퇴 제외) → 기본(menuKind 있음) 먼저 → id.
	 *
	 * @param memberId 로그인 회원이면 그 id(내가 만든 것을 포함), 게스트·비로그인은 null
	 */
	@Query("""
			SELECT new sweetgirl.croissant.breadranking.dto.projection.BreadCategoryVoters(
				c, COUNT(DISTINCT CASE WHEN m.deletedAt IS NULL THEN e.memberId END))
			FROM BreadCategory c
			LEFT JOIN BreadRankEntry e ON e.category = c AND e.rank = 1
			LEFT JOIN Member m ON m.id = e.memberId
			WHERE c.menuKind IS NOT NULL
				OR (:memberId IS NOT NULL AND c.createdBy = :memberId)
				OR c.id IN (
					SELECT t.category.id FROM MemberBreadCategory t
					GROUP BY t.category.id
					HAVING COUNT(DISTINCT t.id.memberId) >= :minTabMembers)
			GROUP BY c
			ORDER BY CASE WHEN c.displayOrder IS NULL THEN 1 ELSE 0 END ASC, c.displayOrder ASC,
				COUNT(DISTINCT CASE WHEN m.deletedAt IS NULL THEN e.memberId END) DESC,
				CASE WHEN c.menuKind IS NULL THEN 1 ELSE 0 END ASC, c.id ASC
			""")
	List<BreadCategoryVoters> findVisibleOrderByDisplay(
			@Param("memberId") Long memberId,
			@Param("minTabMembers") long minTabMembers);
}

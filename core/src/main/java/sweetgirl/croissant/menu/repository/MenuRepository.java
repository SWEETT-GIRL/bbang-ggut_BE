package sweetgirl.croissant.menu.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.menu.dto.projection.StoreMenuProjection;
import sweetgirl.croissant.menu.entity.Menu;

public interface MenuRepository extends JpaRepository<Menu, Long> {

	@Query("""
			SELECT m.store.id AS storeId, m.id AS menuId, m.name AS name, m.price AS price
			FROM Menu m WHERE m.store.id IN :storeIds AND m.deletedAt IS NULL
			ORDER BY m.store.id ASC, m.likeCount DESC
			""")
	List<StoreMenuProjection> findTopRecommendedMenusByStoreIds(@Param("storeIds") List<Long> storeIds);

	@Query("""
			SELECT m FROM Menu m
			JOIN FETCH m.store
			LEFT JOIN FETCH m.menuCategory
			WHERE m.id = :menuId AND m.deletedAt IS NULL
			""")
	Optional<Menu> findByIdWithDetails(@Param("menuId") Long menuId);

	@Query("""
			SELECT m FROM Menu m
			LEFT JOIN FETCH m.menuCategory
			WHERE m.store.id = :storeId AND m.deletedAt IS NULL
			ORDER BY m.likeCount DESC
			""")
	List<Menu> findByStoreIdOrderByLikeCountDesc(@Param("storeId") Long storeId);

	/**
	 * 사진 태그 제안 프롬프트에 넣을 후보. 좋아요가 같으면(대부분 0이다) id 로 갈라 순서를 고정한다 —
	 * 같은 가게·같은 사진에 매번 같은 후보 목록이 가야 답도 흔들리지 않는다.
	 */
	@Query("""
			SELECT m FROM Menu m
			WHERE m.store.id = :storeId AND m.deletedAt IS NULL
			ORDER BY m.likeCount DESC, m.id ASC
			""")
	List<Menu> findByStoreIdOrderByLikeCountDesc(@Param("storeId") Long storeId, Pageable pageable);

	@Query("""
			SELECT m FROM Menu m
			LEFT JOIN FETCH m.menuCategory
			JOIN FETCH m.store
			WHERE m.store.id IN :storeIds AND m.deletedAt IS NULL
			ORDER BY m.store.id ASC, m.likeCount DESC
			""")
	List<Menu> findByStoreIdInOrderByLikeCountDesc(@Param("storeIds") List<Long> storeIds);

	@Query(value = "SELECT m FROM Menu m LEFT JOIN FETCH m.menuCategory JOIN FETCH m.store "
		+ "ORDER BY m.store.id ASC, m.createdAt DESC", countQuery = "SELECT COUNT(m) FROM Menu m")
	Page<Menu> findAllMenusWithDetails(Pageable pageable);

	// 분류 전 메뉴는 menuCategory 가 NULL 이라 INNER JOIN 이면 어드민 목록에서 통째로 빠진다
	@Query("""
			SELECT m FROM Menu m
			LEFT JOIN FETCH m.menuCategory
			WHERE m.store.id = :storeId
			ORDER BY m.createdAt DESC
			""")
	List<Menu> findByStoreIdIncludingDeleted(@Param("storeId") Long storeId);

	@Query("""
			SELECT m.store.id AS storeId, COUNT(m) AS cnt
			FROM Menu m
			WHERE m.store.id IN :storeIds AND m.deletedAt IS NULL
			GROUP BY m.store.id
			""")
	List<Object[]> countActiveMenusByStoreIds(@Param("storeIds") List<Long> storeIds);

	@Modifying
	@Query("UPDATE Menu m SET m.likeCount = m.likeCount + 1 WHERE m.id = :menuId")
	void incrementLikeCount(@Param("menuId") Long menuId);

	@Modifying
	@Query("""
			UPDATE Menu m
			SET m.likeCount = CASE WHEN m.likeCount > 0 THEN m.likeCount - 1 ELSE 0 END
			WHERE m.id = :menuId
			""")
	void decrementLikeCount(@Param("menuId") Long menuId);

	// '메뉴까지 갈아엎기' — store 의 모든 활성 메뉴를 soft-delete (deletedAt 설정).
	@Modifying
	@Query("UPDATE Menu m SET m.deletedAt = CURRENT_TIMESTAMP "
		+ "WHERE m.store.id = :storeId AND m.deletedAt IS NULL")
	int softDeleteByStoreId(@Param("storeId") Long storeId);

	@Query("SELECT m FROM Menu m WHERE m.store.id = :storeId AND m.deletedAt IS NULL")
	List<Menu> findActiveByStoreId(@Param("storeId") Long storeId);

	/**
	 * 같은 가게·같은 정규화 이름의 활성 메뉴 중 가장 오래된 것.
	 * 2026-05-27 이전 수기 데이터에 중복 31그룹이 남아 있어(유니크 인덱스 범위 밖) Optional 로 받으면
	 * IncorrectResultSize 로 500 이 난다 — 정리 전까지 한 건을 고른다. 목록은 scripts/menu_manual_duplicates.py
	 */
	Optional<Menu> findFirstByStoreIdAndNormalizedNameAndDeletedAtIsNullOrderByIdAsc(Long storeId,
			String normalizedName);

	@Modifying(clearAutomatically = true)
	@Query("UPDATE Menu menu SET menu.createdByMember.id = :targetId WHERE menu.createdByMember.id = :guestId")
	int moveCreator(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

	/** AI 분류 대기 — 카테고리 없고, 아직 시도 안 했고, 활성인 것. 오래된 것부터 */
	@Query("SELECT m FROM Menu m WHERE m.menuCategory IS NULL AND m.classifiedAt IS NULL AND m.deletedAt IS NULL "
		+ "ORDER BY m.id")
	List<Menu> findAwaitingClassification(Pageable pageable);

	/** 같은 정규화 이름의 미분류 활성 메뉴 — 어드민이 이름 단위로 kind 를 확정할 때 한꺼번에 적용 */
	List<Menu> findByNormalizedNameAndMenuCategoryIsNullAndDeletedAtIsNull(String normalizedName);

	/**
	 * 빵로그 태그가 가리킬 메뉴 찾기 — 한 기록의 태그 이름들을 한 번에 묻는다.
	 * 위 주석의 중복 31그룹이 여기에도 걸릴 수 있으므로 호출부가 이름당 한 건을 골라 쓴다.
	 */
	List<Menu> findByStoreIdAndDeletedAtIsNullAndNormalizedNameIn(Long storeId, Collection<String> normalizedNames);

	/** 앱이 태그에 실어 보낸 메뉴 id — 그 가게의 활성 메뉴만 인정한다 */
	List<Menu> findByStoreIdAndDeletedAtIsNullAndIdIn(Long storeId, Collection<Long> ids);
}

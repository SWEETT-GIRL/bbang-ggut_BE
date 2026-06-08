package sweetgirl.croissant.menu.repository;

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
			ORDER BY m.store.id ASC, m.recommendCount DESC
			""")
	List<StoreMenuProjection> findTopRecommendedMenusByStoreIds(@Param("storeIds") List<Long> storeIds);

	@Query("""
			SELECT m FROM Menu m
			JOIN FETCH m.store
			JOIN FETCH m.menuCategory
			WHERE m.id = :menuId AND m.deletedAt IS NULL
			""")
	Optional<Menu> findByIdWithDetails(@Param("menuId") Long menuId);

	@Query("""
			SELECT m FROM Menu m
			JOIN FETCH m.menuCategory
			WHERE m.store.id = :storeId AND m.deletedAt IS NULL
			ORDER BY m.recommendCount DESC
			""")
	List<Menu> findByStoreIdOrderByRecommendCountDesc(@Param("storeId") Long storeId);

	@Query(value = "SELECT m FROM Menu m JOIN FETCH m.menuCategory JOIN FETCH m.store "
		+ "ORDER BY m.store.id ASC, m.createdAt DESC", countQuery = "SELECT COUNT(m) FROM Menu m")
	Page<Menu> findAllMenusWithDetails(Pageable pageable);

	@Query("""
			SELECT m FROM Menu m
			JOIN FETCH m.menuCategory
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
	@Query("UPDATE Menu m SET m.recommendCount = m.recommendCount + 1 WHERE m.id = :menuId")
	void incrementRecommendCount(@Param("menuId") Long menuId);

	@Modifying
	@Query("""
			UPDATE Menu m
			SET m.recommendCount = CASE WHEN m.recommendCount > 0 THEN m.recommendCount - 1 ELSE 0 END
			WHERE m.id = :menuId
			""")
	void decrementRecommendCount(@Param("menuId") Long menuId);

	// '메뉴까지 갈아엎기' — store 의 모든 활성 메뉴를 soft-delete (deletedAt 설정).
	@Modifying
	@Query("UPDATE Menu m SET m.deletedAt = CURRENT_TIMESTAMP "
		+ "WHERE m.store.id = :storeId AND m.deletedAt IS NULL")
	int softDeleteByStoreId(@Param("storeId") Long storeId);

	@Query("SELECT m FROM Menu m WHERE m.store.id = :storeId AND m.deletedAt IS NULL")
	List<Menu> findActiveByStoreId(@Param("storeId") Long storeId);
}

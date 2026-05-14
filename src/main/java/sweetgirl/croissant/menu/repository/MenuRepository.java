package sweetgirl.croissant.menu.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
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

	@Query("""
			SELECT m FROM Menu m
			JOIN FETCH m.menuCategory
			WHERE m.store.id = :storeId
			ORDER BY m.createdAt DESC
			""")
	List<Menu> findByStoreIdIncludingDeleted(@Param("storeId") Long storeId);
}

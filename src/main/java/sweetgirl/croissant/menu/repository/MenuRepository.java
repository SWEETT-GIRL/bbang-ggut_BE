package sweetgirl.croissant.menu.repository;

import java.util.List;

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
}

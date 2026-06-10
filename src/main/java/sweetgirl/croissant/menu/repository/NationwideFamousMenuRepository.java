package sweetgirl.croissant.menu.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.menu.entity.NationwideFamousMenu;
import sweetgirl.croissant.menu.enums.MenuCategory;

public interface NationwideFamousMenuRepository extends JpaRepository<NationwideFamousMenu, Long> {

	List<NationwideFamousMenu> findByMenuCategory(MenuCategory menuCategory);
}

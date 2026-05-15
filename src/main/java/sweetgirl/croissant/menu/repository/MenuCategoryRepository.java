package sweetgirl.croissant.menu.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import sweetgirl.croissant.menu.entity.MenuCategory;
import sweetgirl.croissant.menu.enums.MenuKind;

@Repository
public interface MenuCategoryRepository extends JpaRepository<MenuCategory, Long> {
	Optional<MenuCategory> findByName(MenuKind kind);
}

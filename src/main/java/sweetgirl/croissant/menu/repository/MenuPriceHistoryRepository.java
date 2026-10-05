package sweetgirl.croissant.menu.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.menu.entity.MenuPriceHistory;

public interface MenuPriceHistoryRepository extends JpaRepository<MenuPriceHistory, Long> {

}

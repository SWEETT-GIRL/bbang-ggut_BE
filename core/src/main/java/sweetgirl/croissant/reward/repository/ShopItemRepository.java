package sweetgirl.croissant.reward.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.reward.entity.ShopItem;

public interface ShopItemRepository extends JpaRepository<ShopItem, String> {

	/** 파는 품목. 같은 버전이면 같은 순서로 나가야 해서 순서를 못박는다. */
	List<ShopItem> findAllByActiveTrueOrderBySortOrderAscCodeAsc();

	List<ShopItem> findAllByOrderBySortOrderAscCodeAsc();
}

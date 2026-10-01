package sweetgirl.croissant.menu.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.menu.code.MenuErrorCode;
import sweetgirl.croissant.menu.dto.response.MenuPriceResponse;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuPriceHistory;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.MenuPriceHistoryRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;

/**
 * 가게 메뉴 가격 공동 수정 — 빵 평가 화면에서 누구나 고친다. 바뀔 때만 이력을 남긴다(같은 값은 조용히 200).
 * 메뉴에 없는 빵(직접 입력 이름)은 가격을 받을 곳이 없다 — 앱이 가격 칸을 숨긴다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class MenuPriceCommandService {

	public static final int MAX_PRICE = 1_000_000;

	private final MemberReader memberReader;
	private final MenuRepository menuRepository;
	private final MenuPriceHistoryRepository priceHistoryRepository;

	public MenuPriceResponse updatePrice(Long storeId, Long menuId, Long memberId, int price) {
		memberReader.requireLoggedIn(memberId);
		if (price < 1 || price > MAX_PRICE) {
			throw new MenuException(MenuErrorCode.INVALID_PRICE);
		}
		Menu menu = menuRepository.findById(menuId)
			.filter(found -> found.getDeletedAt() == null && found.getStore().getId().equals(storeId))
			.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_NOT_FOUND));
		Integer oldPrice = menu.getPrice();
		if (oldPrice == null || oldPrice != price) {
			menu.updatePrice(price);
			priceHistoryRepository.save(MenuPriceHistory.of(menuId, memberId, oldPrice, price));
		}
		return new MenuPriceResponse(menuId, price);
	}
}

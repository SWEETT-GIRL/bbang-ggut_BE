package sweetgirl.croissant.menu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.menu.code.MenuErrorCode;
import sweetgirl.croissant.menu.dto.response.MenuPriceResponse;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuPriceHistory;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.MenuPriceHistoryRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.entity.Store;

@ExtendWith(MockitoExtension.class)
class MenuPriceCommandServiceTest {

	private static final Long MEMBER_ID = 7L;
	private static final Long STORE_ID = 10L;
	private static final Long MENU_ID = 100L;

	@Mock
	private MemberReader memberReader;

	@Mock
	private MenuRepository menuRepository;

	@Mock
	private MenuPriceHistoryRepository priceHistoryRepository;

	@InjectMocks
	private MenuPriceCommandService service;

	@Test
	@DisplayName("가격을 바꾸면 메뉴에 반영하고 이전·새 값으로 이력을 남긴다")
	void updatePrice_changesAndRecordsHistory() {
		Menu menu = menu(3500);
		given(menuRepository.findById(MENU_ID)).willReturn(Optional.of(menu));

		MenuPriceResponse response = service.updatePrice(STORE_ID, MENU_ID, MEMBER_ID, 3800);

		assertThat(response.price()).isEqualTo(3800);
		assertThat(menu.getPrice()).isEqualTo(3800);
		verify(priceHistoryRepository).save(argThat(
			(MenuPriceHistory history) -> history.getMenuId().equals(MENU_ID) && history.getMemberId().equals(MEMBER_ID)
				&& history.getOldPrice() == 3500 && history.getNewPrice() == 3800));
	}

	@Test
	@DisplayName("가격이 없던 메뉴는 이전 값 null 로 이력을 남긴다")
	void updatePrice_fromEmpty() {
		Menu menu = menu(null);
		given(menuRepository.findById(MENU_ID)).willReturn(Optional.of(menu));

		service.updatePrice(STORE_ID, MENU_ID, MEMBER_ID, 4000);

		verify(priceHistoryRepository).save(argThat((MenuPriceHistory history) -> history.getOldPrice() == null));
	}

	@Test
	@DisplayName("같은 값이면 이력을 남기지 않는다")
	void updatePrice_sameValue_noHistory() {
		given(menuRepository.findById(MENU_ID)).willReturn(Optional.of(menu(3500)));

		service.updatePrice(STORE_ID, MENU_ID, MEMBER_ID, 3500);

		verify(priceHistoryRepository, never()).save(any());
	}

	@Test
	@DisplayName("0원이나 100만 원 초과는 400")
	void updatePrice_outOfRange() {
		for (int price : new int[] {0, -1, 1_000_001}) {
			assertThatThrownBy(() -> service.updatePrice(STORE_ID, MENU_ID, MEMBER_ID, price))
				.isInstanceOf(MenuException.class)
				.extracting("errorCode")
				.isEqualTo(MenuErrorCode.INVALID_PRICE);
		}
		verify(menuRepository, never()).findById(any());
	}

	@Test
	@DisplayName("다른 가게 메뉴·지워진 메뉴는 404")
	void updatePrice_wrongStoreOrDeleted() {
		Menu otherStore = menu(3500);
		ReflectionTestUtils.setField(otherStore.getStore(), "id", 99L);
		given(menuRepository.findById(MENU_ID)).willReturn(Optional.of(otherStore));

		assertThatThrownBy(() -> service.updatePrice(STORE_ID, MENU_ID, MEMBER_ID, 3800))
			.isInstanceOf(MenuException.class)
			.extracting("errorCode")
			.isEqualTo(MenuErrorCode.MENU_NOT_FOUND);
	}

	private static Menu menu(Integer price) {
		Store store = Store.builder().name("밀도 성수").address("서울 성동구").build();
		ReflectionTestUtils.setField(store, "id", STORE_ID);
		Menu menu = Menu.builder().store(store).name("소금빵").price(price).build();
		ReflectionTestUtils.setField(menu, "id", MENU_ID);
		return menu;
	}
}

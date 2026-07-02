package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.external.aws.dto.AdminImageResponse;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreRepository;

@ExtendWith(MockitoExtension.class)
class AdminImageQueryServiceTest {

	@Mock
	StoreRepository storeRepository;
	@Mock
	MenuRepository menuRepository;
	@Mock
	StoreImageFileRepository storeImageFileRepository;
	@Mock
	MenuImageFileRepository menuImageFileRepository;

	@InjectMocks
	AdminImageQueryService adminImageQueryService;

	@Test
	@DisplayName("getStoreImages: 존재하지 않는 가게이면 STORE_NOT_FOUND를 던진다")
	void getStoreImages_storeNotFound_throws() {
		given(storeRepository.existsById(1L)).willReturn(false);

		assertThatThrownBy(() -> adminImageQueryService.getStoreImages(1L))
			.isInstanceOf(StoreException.class);
	}

	@Test
	@DisplayName("getStoreImages: 이미지가 없으면 빈 리스트를 반환한다")
	void getStoreImages_returnsEmptyList_whenNoImages() {
		given(storeRepository.existsById(1L)).willReturn(true);
		given(storeImageFileRepository.findByStoreIdOrderByDisplayOrderAscIdAsc(1L)).willReturn(List.of());

		List<AdminImageResponse> result = adminImageQueryService.getStoreImages(1L);

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("getMenuImages: 존재하지 않는 메뉴이면 MENU_NOT_FOUND를 던진다")
	void getMenuImages_menuNotFound_throws() {
		given(menuRepository.existsById(1L)).willReturn(false);

		assertThatThrownBy(() -> adminImageQueryService.getMenuImages(1L))
			.isInstanceOf(MenuException.class);
	}

	@Test
	@DisplayName("getMenuImages: 이미지가 없으면 빈 리스트를 반환한다")
	void getMenuImages_returnsEmptyList_whenNoImages() {
		given(menuRepository.existsById(1L)).willReturn(true);
		given(menuImageFileRepository.findImagesByMenuId(1L)).willReturn(List.of());

		List<AdminImageResponse> result = adminImageQueryService.getMenuImages(1L);

		assertThat(result).isEmpty();
	}
}

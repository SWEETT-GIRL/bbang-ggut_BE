package sweetgirl.croissant.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Point;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.store.dto.response.SavedStoreResponse;
import sweetgirl.croissant.store.entity.SavedStore;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.repository.SavedStoreRepository;

@ExtendWith(MockitoExtension.class)
class SavedStoreQueryServiceTest {

	@Mock
	SavedStoreRepository savedStoreRepository;

	@InjectMocks
	SavedStoreQueryService savedStoreQueryService;

	@Test
	@DisplayName("getSavedStores: 찜한가게 목록 조회 성공")
	void getSavedStores_success() {
		Long memberId = 1L;

		Store store = mock(Store.class);
		Point point = mock(Point.class);
		given(store.getId()).willReturn(10L);
		given(store.getName()).willReturn("소금빵 가게");
		given(store.getRegion2nd()).willReturn("마포구");
		given(store.getLocation()).willReturn(point);
		given(point.getY()).willReturn(37.55);
		given(point.getX()).willReturn(126.92);

		SavedStore savedStore = mock(SavedStore.class);
		given(savedStore.getStore()).willReturn(store);

		given(savedStoreRepository.findAllByMemberId(memberId)).willReturn(List.of(savedStore));

		List<SavedStoreResponse> result = savedStoreQueryService.getSavedStores(memberId);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).storeId()).isEqualTo(10L);
		assertThat(result.get(0).storeName()).isEqualTo("소금빵 가게");
		assertThat(result.get(0).region()).isEqualTo("마포구");
		assertThat(result.get(0).latitude()).isEqualTo(37.55);
		assertThat(result.get(0).longitude()).isEqualTo(126.92);
	}
}

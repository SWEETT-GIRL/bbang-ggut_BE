package sweetgirl.croissant.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Point;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.store.dto.projection.StoreImageThumbnailProjection;
import sweetgirl.croissant.store.dto.response.SavedStoreResponse;
import sweetgirl.croissant.store.entity.SavedStore;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.repository.SavedStoreRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;

@ExtendWith(MockitoExtension.class)
class SavedStoreQueryServiceTest {

	@Mock
	SavedStoreRepository savedStoreRepository;

	@Mock
	StoreImageFileRepository storeImageFileRepository;

	@InjectMocks
	SavedStoreQueryService savedStoreQueryService;

	private SavedStore mockSavedStore(Long storeId) {
		Store store = mock(Store.class);
		Point point = mock(Point.class);
		given(store.getId()).willReturn(storeId);
		given(store.getName()).willReturn("소금빵 가게");
		given(store.getRegion2nd()).willReturn("마포구");
		given(store.getLocation()).willReturn(point);
		given(point.getY()).willReturn(37.55);
		given(point.getX()).willReturn(126.92);

		SavedStore savedStore = mock(SavedStore.class);
		given(savedStore.getStore()).willReturn(store);
		return savedStore;
	}

	private StoreImageThumbnailProjection mockThumbnail(Long storeId, String resizedKey320, String awsKey) {
		StoreImageThumbnailProjection thumbnail = mock(StoreImageThumbnailProjection.class);
		given(thumbnail.getStoreId()).willReturn(storeId);
		given(thumbnail.getResizedKey320()).willReturn(resizedKey320);
		lenient().when(thumbnail.getAwsKey()).thenReturn(awsKey);
		return thumbnail;
	}

	@Test
	@DisplayName("getSavedStores: 찜한가게 목록 조회 성공 (리사이즈 썸네일 우선)")
	void getSavedStores_success() {
		Long memberId = 1L;
		SavedStore savedStore = mockSavedStore(10L);

		StoreImageThumbnailProjection thumbnail = mockThumbnail(10L, "resized/320/store10.jpg", "origin/store10.jpg");
		given(savedStoreRepository.findAllByMemberId(memberId)).willReturn(List.of(savedStore));
		given(storeImageFileRepository.findThumbnailsByStoreIdIn(List.of(10L)))
			.willReturn(List.of(thumbnail));

		List<SavedStoreResponse> result = savedStoreQueryService.getSavedStores(memberId);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).storeId()).isEqualTo(10L);
		assertThat(result.get(0).storeName()).isEqualTo("소금빵 가게");
		assertThat(result.get(0).region()).isEqualTo("마포구");
		assertThat(result.get(0).latitude()).isEqualTo(37.55);
		assertThat(result.get(0).longitude()).isEqualTo(126.92);
		assertThat(result.get(0).thumbnailImageKey()).isEqualTo("resized/320/store10.jpg");
	}

	@Test
	@DisplayName("getSavedStores: 리사이즈 키가 없으면 awsKey로 fallback")
	void getSavedStores_fallbackToAwsKey() {
		Long memberId = 1L;
		SavedStore savedStore = mockSavedStore(10L);

		StoreImageThumbnailProjection thumbnail = mockThumbnail(10L, null, "origin/store10.jpg");
		given(savedStoreRepository.findAllByMemberId(memberId)).willReturn(List.of(savedStore));
		given(storeImageFileRepository.findThumbnailsByStoreIdIn(List.of(10L)))
			.willReturn(List.of(thumbnail));

		List<SavedStoreResponse> result = savedStoreQueryService.getSavedStores(memberId);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).thumbnailImageKey()).isEqualTo("origin/store10.jpg");
	}

	@Test
	@DisplayName("getSavedStores: 썸네일이 없으면 thumbnailImageKey는 null")
	void getSavedStores_noThumbnail() {
		Long memberId = 1L;
		SavedStore savedStore = mockSavedStore(10L);

		given(savedStoreRepository.findAllByMemberId(memberId)).willReturn(List.of(savedStore));
		given(storeImageFileRepository.findThumbnailsByStoreIdIn(List.of(10L))).willReturn(List.of());

		List<SavedStoreResponse> result = savedStoreQueryService.getSavedStores(memberId);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).thumbnailImageKey()).isNull();
	}

	@Test
	@DisplayName("getSavedStores: 찜한 가게가 없으면 빈 목록 반환")
	void getSavedStores_empty() {
		Long memberId = 1L;
		given(savedStoreRepository.findAllByMemberId(memberId)).willReturn(List.of());

		List<SavedStoreResponse> result = savedStoreQueryService.getSavedStores(memberId);

		assertThat(result).isEmpty();
	}
}

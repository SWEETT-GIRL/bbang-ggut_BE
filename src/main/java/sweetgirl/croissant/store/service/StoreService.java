package sweetgirl.croissant.store.service;

import static sweetgirl.croissant.store.code.StoreConstants.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.common.enums.StoreStatus;
import sweetgirl.croissant.external.kakao.client.KakaoLocalClient;
import sweetgirl.croissant.external.kakao.dto.KakaoPlace;
import sweetgirl.croissant.external.kakao.dto.KakaoSearchResult;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.dto.NearbyStoreProjection;
import sweetgirl.croissant.store.dto.NearbyStoreResponse;
import sweetgirl.croissant.store.dto.StoreDetailResponse;
import sweetgirl.croissant.store.dto.StoreImageThumbnailProjection;
import sweetgirl.croissant.store.dto.StoreSearchResponse;
import sweetgirl.croissant.store.dto.SubscribedStoreResponse;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreBusinessHour;
import sweetgirl.croissant.store.entity.StoreImageFile;
import sweetgirl.croissant.store.entity.SubscribedStore;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreBusinessHourRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreRepository;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreService {

	private final StoreRepository storeRepository;
	private final StoreBusinessHourRepository storeBusinessHourRepository;
	private final StoreImageFileRepository storeImageFileRepository;
	private final SubscribedStoreRepository subscribedStoreRepository;
	private final KakaoLocalClient kakaoLocalClient;
	private final StoreAsyncService storeAsyncService;

	public StoreDetailResponse getStoreDetail(Long storeId) {
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		List<StoreBusinessHour> businessHours = storeBusinessHourRepository.findByStoreIdOrderByDayOfWeekAsc(storeId);
		List<StoreImageFile> images = storeImageFileRepository.findByStoreId(storeId);
		return StoreDetailResponse.of(store, businessHours, images);
	}

	public SliceResponse<SubscribedStoreResponse> getSubscribedStores(Member member, Pageable pageable) {
		Slice<SubscribedStore> slice = subscribedStoreRepository.findAllByMemberOrderByCreatedAtDesc(member, pageable);
		Slice<SubscribedStoreResponse> responsSlice = slice.map(subscribedStore -> {
			List<StoreImageFile> images = storeImageFileRepository.findByStoreId(subscribedStore.getStore().getId());
			String imageUrl = images.isEmpty() ? null : images.get(0).getAwsKey();
			return SubscribedStoreResponse.from(subscribedStore, imageUrl);
		});
		return SliceResponse.from(responsSlice);
	}

	public SliceResponse<StoreSearchResponse> searchStores(String keyword, Pageable pageable) {
		Slice<Store> dbResult = storeRepository.findByNameContainingAndStatus(keyword, StoreStatus.ACTIVE, pageable);

		if (!dbResult.isEmpty()) {
			return SliceResponse.from(dbResult.map(store -> {
				String thumbnailUrl = storeImageFileRepository.findFirstByStoreIdOrderByIdAsc(store.getId())
					.map(StoreImageFile::getAwsKey)
					.orElse(null);
				return StoreSearchResponse.ofStore(store, thumbnailUrl);
			}));
		}

		KakaoSearchResult kakaoResult = kakaoLocalClient.searchByKeyword(keyword, pageable.getPageNumber() + 1);
		List<KakaoPlace> places = kakaoResult.places();

		if (!places.isEmpty()) {
			storeAsyncService.saveKakaoPlaces(places);
		}

		List<StoreSearchResponse> responses = places.stream()
			.map(StoreSearchResponse::ofKakao)
			.toList();
		return new SliceResponse<>(responses, !kakaoResult.isEnd());
	}

	public SliceResponse<NearbyStoreResponse> getNearbyStores(double longitude, double latitude, Pageable pageable) {
		Slice<NearbyStoreProjection> storeSlice = storeRepository.findNearbyStores(longitude, latitude,
			SEARCH_NEARBY_RADIUS, pageable);
		if (!storeSlice.hasContent()) {
			Slice<NearbyStoreResponse> emptySlice = new SliceImpl<>(Collections.emptyList(), pageable, false);
			return SliceResponse.from(emptySlice);
		}

		List<Long> storeIds = storeSlice.getContent().stream()
			.map(NearbyStoreProjection::getId)
			.toList();
		Map<Long, String> storeThumbnailMap = getStoreThumbnails(storeIds);

		Slice<NearbyStoreResponse> responseSlice = storeSlice.map(nearbyStore -> {
			int roundedDistance = (int)Math.round(nearbyStore.getDistanceMeters());
			String storeThumbnailUrl = storeThumbnailMap.get(nearbyStore.getId());

			return NearbyStoreResponse.of(nearbyStore, storeThumbnailUrl, roundedDistance);
		});
		return SliceResponse.from(responseSlice);
	}

	private Map<Long, String> getStoreThumbnails(List<Long> storeIds) {
		return storeImageFileRepository.findThumbnailsByStoreIdIn(storeIds).stream()
			.collect(Collectors.toMap(
				StoreImageThumbnailProjection::getStoreId,
				StoreImageThumbnailProjection::getAwsKey,
				(first, second) -> first));
	}
}

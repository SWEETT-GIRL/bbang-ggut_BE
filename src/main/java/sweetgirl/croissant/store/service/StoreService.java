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
import sweetgirl.croissant.external.kakao.client.KakaoLocalClient;
import sweetgirl.croissant.external.kakao.dto.KakaoPlace;
import sweetgirl.croissant.external.kakao.dto.KakaoSearchResult;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.menu.dto.dto.MenuSummaryDto;
import sweetgirl.croissant.menu.dto.projection.MenuImageThumbnailProjection;
import sweetgirl.croissant.menu.dto.projection.StoreMenuProjection;
import sweetgirl.croissant.menu.entity.MenuImageFile;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.dto.projection.DistrictStoreProjection;
import sweetgirl.croissant.store.dto.projection.NearbyStoreProjection;
import sweetgirl.croissant.store.dto.projection.StoreImageThumbnailProjection;
import sweetgirl.croissant.store.dto.projection.TopSubscribedStoreProjection;
import sweetgirl.croissant.store.dto.request.KakaoStoreRegisterRequest;
import sweetgirl.croissant.store.dto.response.DistrictStoreResponse;
import sweetgirl.croissant.store.dto.response.KakaoStoreRegisterResponse;
import sweetgirl.croissant.store.dto.response.NearbyStoreResponse;
import sweetgirl.croissant.store.dto.response.StoreDetailResponse;
import sweetgirl.croissant.store.dto.response.StoreSearchResponse;
import sweetgirl.croissant.store.dto.response.SubscribedStoreResponse;
import sweetgirl.croissant.store.dto.response.TopSubscribedStoreResponse;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreBusinessHour;
import sweetgirl.croissant.store.entity.StoreImageFile;
import sweetgirl.croissant.store.entity.SubscribedStore;
import sweetgirl.croissant.store.enums.District;
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
	private final MemberReader memberReader;
	private final MenuImageFileRepository menuImageFileRepository;
	private final MenuRepository menuRepository;

	public StoreDetailResponse getStoreDetail(Long memberId, Long storeId) {
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		List<StoreBusinessHour> businessHours = storeBusinessHourRepository.findByStoreIdOrderByDayOfWeekAsc(storeId);
		List<StoreImageFile> images = storeImageFileRepository.findByStoreIdOrderByDisplayOrderAscIdAsc(storeId);
		List<MenuImageFile> menuImages = menuImageFileRepository.findByMenuStoreId(storeId);
		boolean isSubscribed = memberId != null
			&& subscribedStoreRepository.existsByMemberIdAndStoreId(memberId, storeId);
		return StoreDetailResponse.of(store, businessHours, images, menuImages, isSubscribed);
	}

	public SliceResponse<SubscribedStoreResponse> getSubscribedStores(Long memberId, Pageable pageable) {
		Member member = memberReader.getMember(memberId);
		Slice<SubscribedStore> slice = subscribedStoreRepository.findAllByMemberOrderByCreatedAtDesc(member, pageable);
		Slice<SubscribedStoreResponse> responsSlice = slice.map(subscribedStore -> {
			List<StoreImageFile> images = storeImageFileRepository
				.findByStoreIdOrderByDisplayOrderAscIdAsc(subscribedStore.getStore().getId());
			String imageUrl = images.isEmpty() ? null : images.get(0).getAwsKey();
			return SubscribedStoreResponse.from(subscribedStore, imageUrl);
		});
		return SliceResponse.from(responsSlice);
	}

	public SliceResponse<StoreSearchResponse> searchStores(String keyword, Pageable pageable) {
		Slice<Store> dbSlice = storeRepository.findActiveWesternStoresByKeyword(keyword, pageable);
		if (dbSlice.hasContent()) {
			return buildResponseFromDb(dbSlice);
		}
		return searchFromKakao(keyword, pageable);
	}

	private SliceResponse<StoreSearchResponse> buildResponseFromDb(Slice<Store> dbSlice) {
		List<Long> storeIds = dbSlice.getContent().stream().map(Store::getId).toList();
		Map<Long, String> storeThumbnailMap = getStoreThumbnails(storeIds);
		Map<Long, List<StoreMenuProjection>> topMenusByStore = getTopMenusByStore(storeIds);
		Map<Long, String> menuThumbnailMap = getMenuThumbnails(topMenusByStore);

		List<StoreSearchResponse> responses = dbSlice.getContent().stream()
			.map(store -> {
				List<MenuSummaryDto> topMenus = topMenusByStore
					.getOrDefault(store.getId(), Collections.emptyList())
					.stream()
					.map(menu -> MenuSummaryDto.of(menu, menuThumbnailMap.get(menu.getMenuId())))
					.toList();
				return StoreSearchResponse.ofDb(store, storeThumbnailMap.get(store.getId()), topMenus);
			})
			.toList();

		return new SliceResponse<>(responses, dbSlice.hasNext());
	}

	private SliceResponse<StoreSearchResponse> searchFromKakao(String keyword, Pageable pageable) {
		KakaoSearchResult kakaoResult = kakaoLocalClient.searchByKeyword(keyword, pageable.getPageNumber() + 1);
		List<KakaoPlace> places = kakaoResult.places();

		List<String> kakaoPlaceIds = places.stream().map(KakaoPlace::id).toList();
		Map<String, Store> dbStoreByKakaoPlaceId = storeRepository.findByKakaoPlaceIdIn(kakaoPlaceIds)
			.stream()
			.collect(Collectors.toMap(Store::getKakaoPlaceId, store -> store));

		List<Long> matchedStoreIds = dbStoreByKakaoPlaceId.values().stream().map(Store::getId).toList();
		Map<Long, String> storeThumbnailMap = matchedStoreIds.isEmpty()
			? Collections.emptyMap() : getStoreThumbnails(matchedStoreIds);
		Map<Long, List<StoreMenuProjection>> topMenusByStore = matchedStoreIds.isEmpty()
			? Collections.emptyMap() : getTopMenusByStore(matchedStoreIds);
		Map<Long, String> menuThumbnailMap = getMenuThumbnails(topMenusByStore);

		List<StoreSearchResponse> responses = places.stream()
			.map(place -> {
				Store dbStore = dbStoreByKakaoPlaceId.get(place.id());
				if (dbStore != null) {
					String thumbnailUrl = storeThumbnailMap.get(dbStore.getId());
					List<MenuSummaryDto> topMenus = topMenusByStore
						.getOrDefault(dbStore.getId(), Collections.emptyList())
						.stream()
						.map(menu -> MenuSummaryDto.of(menu, menuThumbnailMap.get(menu.getMenuId())))
						.toList();
					return StoreSearchResponse.ofKakao(place, dbStore.getId(), thumbnailUrl, topMenus);
				}
				return StoreSearchResponse.ofKakao(place, null, null, Collections.emptyList());
			})
			.toList();

		return new SliceResponse<>(responses, !kakaoResult.isEnd());
	}

	public SliceResponse<DistrictStoreResponse> getStoresByDistrict(String city, double lat, double lng,
			Pageable pageable) {
		District district = District.fromCity(city)
			.orElseThrow(() -> new StoreException(StoreErrorCode.INVALID_DISTRICT));

		Slice<DistrictStoreProjection> storeSlice = storeRepository.findStoresByDistrictWithStats(
			district.name(), lng, lat, pageable);

		if (!storeSlice.hasContent()) {
			return SliceResponse.from(new SliceImpl<>(Collections.emptyList(), pageable, false));
		}

		List<Long> storeIds = storeSlice.getContent().stream()
			.map(DistrictStoreProjection::getId)
			.toList();
		Map<Long, String> storeThumbnailMap = getStoreThumbnails(storeIds);
		Map<Long, List<StoreMenuProjection>> topMenusByStore = getTopMenusByStore(storeIds);
		Map<Long, String> menuThumbnailMap = getMenuThumbnails(topMenusByStore);

		Slice<DistrictStoreResponse> responseSlice = storeSlice.map(store -> {
			String storeThumbnailUrl = storeThumbnailMap.get(store.getId());
			List<MenuSummaryDto> topMenus = topMenusByStore.getOrDefault(store.getId(), Collections.emptyList())
				.stream()
				.map(menu -> MenuSummaryDto.of(menu, menuThumbnailMap.get(menu.getMenuId())))
				.toList();
			return DistrictStoreResponse.of(store, storeThumbnailUrl, topMenus);
		});

		return SliceResponse.from(responseSlice);
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

	public List<TopSubscribedStoreResponse> getTopSubscribedStores(double longitude, double latitude) {
		List<TopSubscribedStoreProjection> topStores = storeRepository.findTopSubscribedStores(longitude, latitude,
			TOP_SUBSCRIBED_STORE_LIMIT);
		List<Long> storeIds = topStores.stream()
			.map(TopSubscribedStoreProjection::getId)
			.toList();
		Map<Long, String> storeThumbnailMap = getStoreThumbnails(storeIds);

		return topStores.stream()
			.map(store -> TopSubscribedStoreResponse.of(store, storeThumbnailMap.get(store.getId()),
				Math.round(store.getDistanceMeters())))
			.toList();
	}

	@Transactional
	public KakaoStoreRegisterResponse registerKakaoStore(KakaoStoreRegisterRequest request) {
		District district = District.fromAddress(request.address()).orElse(null);
		storeRepository.insertIfNotExistsByKakaoPlaceId(
			request.name(), request.address(),
			district != null ? district.name() : null,
			Double.parseDouble(request.x()), Double.parseDouble(request.y()),
			request.phone(), request.kakaoPlaceId());
		Long storeId = storeRepository.findByKakaoPlaceId(request.kakaoPlaceId())
			.map(Store::getId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		return new KakaoStoreRegisterResponse(storeId);
	}

	@Transactional
	public void subscribeStore(Long storeId, Long memberId) {
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		if (subscribedStoreRepository.existsByMemberIdAndStoreId(memberId, storeId)) {
			throw new StoreException(StoreErrorCode.ALREADY_SUBSCRIBED);
		}
		subscribedStoreRepository.save(SubscribedStore.builder()
			.member(memberReader.getMember(memberId))
			.store(store)
			.build());
	}

	@Transactional
	public void unsubscribeStore(Long storeId, Long memberId) {
		if (!storeRepository.existsById(storeId)) {
			throw new StoreException(StoreErrorCode.STORE_NOT_FOUND);
		}
		SubscribedStore subscribedStore = subscribedStoreRepository.findByMemberIdAndStoreId(memberId, storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.NOT_SUBSCRIBED));
		subscribedStoreRepository.delete(subscribedStore);
	}

	private Map<Long, String> getStoreThumbnails(List<Long> storeIds) {
		return storeImageFileRepository.findThumbnailsByStoreIdIn(storeIds).stream()
			.collect(Collectors.toMap(
				StoreImageThumbnailProjection::getStoreId,
				StoreImageThumbnailProjection::getAwsKey,
				(first, second) -> first));
	}

	private Map<Long, List<StoreMenuProjection>> getTopMenusByStore(List<Long> storeIds) {
		return menuRepository.findTopRecommendedMenusByStoreIds(storeIds).stream()
			.collect(Collectors.groupingBy(
				StoreMenuProjection::getStoreId,
				Collectors.collectingAndThen(
					Collectors.toList(),
					list -> list.stream().limit(TOP_MENU_LIMIT).toList())));
	}

	private Map<Long, String> getMenuThumbnails(Map<Long, List<StoreMenuProjection>> menusByStore) {
		List<Long> menuIds = menusByStore.values().stream()
			.flatMap(List::stream)
			.map(StoreMenuProjection::getMenuId)
			.toList();

		if (menuIds.isEmpty()) {
			return Collections.emptyMap();
		}

		return menuImageFileRepository.findThumbnailsByMenuIdIn(menuIds).stream()
			.collect(Collectors.toMap(
				MenuImageThumbnailProjection::getMenuId,
				MenuImageThumbnailProjection::getAwsKey,
				(first, second) -> first));
	}
}

package sweetgirl.croissant.store.service;

import static sweetgirl.croissant.store.code.StoreConstants.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.menu.dto.dto.MenuSummaryDto;
import sweetgirl.croissant.menu.dto.projection.MenuImageThumbnailProjection;
import sweetgirl.croissant.menu.dto.projection.StoreMenuProjection;
import sweetgirl.croissant.menu.entity.MenuImageFile;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.AddressParser;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.dto.projection.NearbyStoreProjection;
import sweetgirl.croissant.store.dto.projection.RegionByDistanceProjection;
import sweetgirl.croissant.store.dto.projection.RegionBySubscribeProjection;
import sweetgirl.croissant.store.dto.projection.StoreImageThumbnailProjection;
import sweetgirl.croissant.store.dto.projection.TopSubscribedStoreProjection;
import sweetgirl.croissant.store.dto.request.KakaoStoreRegisterRequest;
import sweetgirl.croissant.store.dto.response.KakaoStoreRegisterResponse;
import sweetgirl.croissant.store.dto.response.NearbyStoreResponse;
import sweetgirl.croissant.store.dto.response.RegionByDistanceResponse;
import sweetgirl.croissant.store.dto.response.RegionBySubscribeResponse;
import sweetgirl.croissant.store.dto.response.StoreDetailResponse;
import sweetgirl.croissant.store.dto.response.SubscribedStoreResponse;
import sweetgirl.croissant.store.dto.response.TopSubscribedStoreResponse;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreBusinessHour;
import sweetgirl.croissant.store.entity.StoreImageFile;
import sweetgirl.croissant.store.entity.SubscribedStore;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.SavedStoreRepository;
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
	private final SavedStoreRepository savedStoreRepository;
	private final StoreAsyncService storeAsyncService;
	private final MemberReader memberReader;
	private final MenuImageFileRepository menuImageFileRepository;
	private final MenuRepository menuRepository;

	public StoreDetailResponse getStoreDetail(Long memberId, Long storeId) {
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		List<StoreBusinessHour> businessHours = storeBusinessHourRepository.findByStoreIdOrderByDayOfWeekAsc(storeId);
		List<StoreImageFile> images = storeImageFileRepository.findByStoreIdOrderByDisplayOrderAscIdAsc(storeId);
		List<MenuImageFile> menuImages = menuImageFileRepository.findByMenuStoreIdOrderByDisplayOrderAscIdAsc(storeId);
		boolean isSubscribed = memberId != null
			&& subscribedStoreRepository.existsByMemberIdAndStoreId(memberId, storeId);
		boolean isSaved = memberId != null
			&& savedStoreRepository.existsByMemberIdAndStoreId(memberId, storeId);
		return StoreDetailResponse.of(store, businessHours, images, menuImages, isSubscribed, isSaved);
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

	public SliceResponse<RegionByDistanceResponse> getStoresByDistrictOrderByDistance(
			String keyword, double lat, double lng, Pageable pageable) {
		String[] parts = AddressParser.parse(keyword);
		String region1st = parts[0];
		String region2nd = parts[1];
		String region3rd = parts[2];

		Slice<RegionByDistanceProjection> storeSlice;
		if (region3rd != null) {
			storeSlice = storeRepository.findStoresByRegion3rdOrderByDistance(region3rd, region2nd, region1st, lng, lat,
				pageable);
		} else if (region2nd != null) {
			storeSlice = storeRepository.findStoresByRegion2ndOrderByDistance(region2nd, region1st, lng, lat, pageable);
		} else {
			storeSlice = storeRepository.findStoresByRegion1stOrderByDistance(region1st, lng, lat, pageable);
		}

		if (!storeSlice.hasContent()) {
			return SliceResponse.from(new SliceImpl<>(Collections.emptyList(), pageable, false));
		}

		List<Long> storeIds = storeSlice.getContent().stream()
			.map(RegionByDistanceProjection::getId)
			.toList();
		Map<Long, String> storeThumbnailMap = getStoreThumbnails(storeIds);
		Map<Long, List<StoreMenuProjection>> topMenusByStore = getTopMenusByStore(storeIds);
		Map<Long, String> menuThumbnailMap = getMenuThumbnails(topMenusByStore);

		Slice<RegionByDistanceResponse> responseSlice = storeSlice.map(store -> {
			List<MenuSummaryDto> topMenus = topMenusByStore.getOrDefault(store.getId(), Collections.emptyList())
				.stream()
				.map(menu -> MenuSummaryDto.of(menu, menuThumbnailMap.get(menu.getMenuId())))
				.toList();
			return RegionByDistanceResponse.of(store, storeThumbnailMap.get(store.getId()), topMenus);
		});

		return SliceResponse.from(responseSlice);
	}

	public SliceResponse<RegionBySubscribeResponse> getStoresByDistrictOrderBySubscribe(
			String keyword, Pageable pageable) {
		String[] parts = AddressParser.parse(keyword);
		String region1st = parts[0];
		String region2nd = parts[1];
		String region3rd = parts[2];

		Slice<RegionBySubscribeProjection> storeSlice;
		if (region3rd != null) {
			storeSlice = storeRepository.findStoresByRegion3rdOrderBySubscribeCount(region3rd, region2nd, region1st,
				pageable);
		} else if (region2nd != null) {
			storeSlice = storeRepository.findStoresByRegion2ndOrderBySubscribeCount(region2nd, region1st, pageable);
		} else {
			storeSlice = storeRepository.findStoresByRegion1stOrderBySubscribeCount(region1st, pageable);
		}

		if (!storeSlice.hasContent()) {
			return SliceResponse.from(new SliceImpl<>(Collections.emptyList(), pageable, false));
		}

		List<Long> storeIds = storeSlice.getContent().stream()
			.map(RegionBySubscribeProjection::getId)
			.toList();
		Map<Long, String> storeThumbnailMap = getStoreThumbnails(storeIds);
		Map<Long, List<StoreMenuProjection>> topMenusByStore = getTopMenusByStore(storeIds);
		Map<Long, String> menuThumbnailMap = getMenuThumbnails(topMenusByStore);

		Slice<RegionBySubscribeResponse> responseSlice = storeSlice.map(store -> {
			List<MenuSummaryDto> topMenus = topMenusByStore.getOrDefault(store.getId(), Collections.emptyList())
				.stream()
				.map(menu -> MenuSummaryDto.of(menu, menuThumbnailMap.get(menu.getMenuId())))
				.toList();
			return RegionBySubscribeResponse.of(store, storeThumbnailMap.get(store.getId()), topMenus);
		});

		return SliceResponse.from(responseSlice);
	}

	public SliceResponse<NearbyStoreResponse> getNearbyStores(double longitude, double latitude, Pageable pageable) {
		Slice<NearbyStoreProjection> storeSlice = storeRepository.findNearbyStores(longitude, latitude,
			SEARCH_NEARBY_RADIUS, pageable);
		if (!storeSlice.hasContent()) {
			return SliceResponse.from(new SliceImpl<>(Collections.emptyList(), pageable, false));
		}

		List<Long> storeIds = storeSlice.getContent().stream()
			.map(NearbyStoreProjection::getId)
			.toList();
		Map<Long, String> storeThumbnailMap = getStoreThumbnails(storeIds);

		Slice<NearbyStoreResponse> responseSlice = storeSlice.map(nearbyStore -> {
			int roundedDistance = (int)Math.round(nearbyStore.getDistanceMeters());
			return NearbyStoreResponse.of(nearbyStore, storeThumbnailMap.get(nearbyStore.getId()), roundedDistance);
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
		Optional<Store> storeByKakaoPlaceId = storeRepository.findByKakaoPlaceId(request.kakaoPlaceId());
		if (storeByKakaoPlaceId.isPresent()) {
			return new KakaoStoreRegisterResponse(storeByKakaoPlaceId.get().getId());
		}

		Optional<Store> storeByIdentity = findActiveStoreBySameNameAndAddress(request);
		if (storeByIdentity.isPresent()) {
			Store store = storeByIdentity.get();
			storeRepository.updateKakaoPlaceMetadata(
				store.getId(),
				request.kakaoPlaceId(),
				request.phone(),
				Double.parseDouble(request.x()),
				Double.parseDouble(request.y()));
			return new KakaoStoreRegisterResponse(store.getId());
		}

		String[] addr = AddressParser.parse(request.addressName() != null ? request.addressName() : request.address());
		storeRepository.insertIfNotExistsByKakaoPlaceId(
			request.name(), request.address(),
			addr[0], addr[1], addr[2],
			Double.parseDouble(request.x()), Double.parseDouble(request.y()),
			request.phone(), request.kakaoPlaceId(),
			null);
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

	private Optional<Store> findActiveStoreBySameNameAndAddress(KakaoStoreRegisterRequest request) {
		String compactName = compactName(request.name());
		String normalizedAddress = normalizeAddressForDuplicate(request.address());
		if (compactName.isBlank() || normalizedAddress.isBlank()) {
			return Optional.empty();
		}
		return storeRepository.findActiveStoresByCompactName(compactName)
			.stream()
			.filter(store -> normalizedAddress.equals(normalizeAddressForDuplicate(store.getAddress())))
			.findFirst();
	}

	private String compactName(String value) {
		if (value == null) {
			return "";
		}
		return value.toLowerCase().replaceAll("\\s+", "");
	}

	private String normalizeAddressForDuplicate(String value) {
		if (value == null) {
			return "";
		}
		return value.toLowerCase()
			.replaceAll("(?:지하)?\\d+층", "")
			.replaceAll("\\d+호", "")
			.replaceAll("[^0-9a-zA-Z가-힣ㄱ-ㅎㅏ-ㅣ]", "");
	}

	private Map<Long, String> getStoreThumbnails(List<Long> storeIds) {
		return storeImageFileRepository.findThumbnailsByStoreIdIn(storeIds).stream()
			.collect(Collectors.toMap(
				StoreImageThumbnailProjection::getStoreId,
				StoreImageThumbnailProjection::getResizedKey320,
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
				MenuImageThumbnailProjection::getResizedKey320,
				(first, second) -> first));
	}
}

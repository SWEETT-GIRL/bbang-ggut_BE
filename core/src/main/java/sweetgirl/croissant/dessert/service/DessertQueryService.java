package sweetgirl.croissant.dessert.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.common.util.MapBounds;
import sweetgirl.croissant.dessert.code.DessertErrorCode;
import sweetgirl.croissant.dessert.dto.projection.DessertStoreProjection;
import sweetgirl.croissant.dessert.dto.response.DessertStoreResponse;
import sweetgirl.croissant.dessert.dto.response.HotDessertResponse;
import sweetgirl.croissant.dessert.entity.DessertItem;
import sweetgirl.croissant.dessert.enums.DessertTrendStatus;
import sweetgirl.croissant.dessert.exception.DessertException;
import sweetgirl.croissant.dessert.repository.ChainStoreRepository;
import sweetgirl.croissant.dessert.repository.ConvenienceStoreRepository;
import sweetgirl.croissant.dessert.repository.DessertItemRepository;
import sweetgirl.croissant.store.dto.projection.StoreImageThumbnailProjection;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;

/**
 * 지도의 '요즘 뜨는 디저트'. 표는 디저트 파이프라인이 채우고 여기서는 읽기만 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DessertQueryService {

	/** 한 번에 세울 핀 수의 상한. 편의점은 전국 5만 곳이라 상한이 없으면 지도가 핀으로 덮인다 */
	public static final int MAX_STORE_SIZE = 50;

	/** 지도 영역으로 찾을 때의 기본·상한. 영역이 곧 범위라 가까운 몇 곳이 아니라 보이는 곳을 다 세운다 */
	public static final int DEFAULT_BOUNDS_STORE_SIZE = 150;
	public static final int MAX_BOUNDS_STORE_SIZE = 300;

	/** 반경 기본값. "내 주변"이라 부를 수 있는 한계이자, GIST 인덱스를 태우는 조건이다 */
	public static final double DEFAULT_RADIUS_METERS = 30_000;
	/** 반경 상한. 비로그인 엔드포인트라 반경을 크게 주면 전국을 재게 된다 — 넘으면 여기까지로 줄인다 */
	public static final double MAX_RADIUS_METERS = 50_000;

	/** trend_status 는 nullable 이다. 판정이 아직 없는 것은 판정된 것들 뒤에 세운다 */
	private static final int UNJUDGED_CHIP_ORDER = Integer.MAX_VALUE;

	private final DessertItemRepository dessertItemRepository;
	private final ConvenienceStoreRepository convenienceStoreRepository;
	private final ChainStoreRepository chainStoreRepository;
	private final StoreImageFileRepository storeImageFileRepository;

	/** 칩 줄 — 뜨는 것부터, 같은 판정 안에서는 먼저 들어온 것부터 */
	public List<HotDessertResponse> getHotDesserts() {
		return dessertItemRepository.findByActiveTrue().stream()
			.sorted(Comparator.comparingInt(DessertQueryService::chipOrderOf)
				.thenComparing(DessertItem::getId))
			.map(HotDessertResponse::from)
			.toList();
	}

	/**
	 * 이 디저트를 파는 곳, 가까운 순.
	 *
	 * <p>편의점 상품은 브랜드 전체에서 팔아 가게 단위 연결이 없다 — 그 브랜드의 가까운 지점을 준다.
	 * 나머지(GENERIC·CHAIN)는 파이프라인이 블로그·인스타 근거로 붙여 둔 가게를 준다.
	 * 어느 쪽이든 응답 모양은 같다.
	 */
	public List<DessertStoreResponse> getSellingStores(
			Long dessertItemId,
			double latitude,
			double longitude,
			int size,
			Double radiusMeters) {
		validateCoordinate(latitude, longitude);
		DessertItem dessertItem = dessertItemRepository.findById(dessertItemId)
			.orElseThrow(() -> new DessertException(DessertErrorCode.DESSERT_NOT_FOUND));

		PageRequest limit = PageRequest.of(0, Math.min(Math.max(size, 1), MAX_STORE_SIZE));
		double radius = radiusMeters == null ? DEFAULT_RADIUS_METERS : Math.min(radiusMeters, MAX_RADIUS_METERS);

		// enum 을 다 훑는 switch 라, 네 번째 성격이 생기면 여기서 컴파일이 깨진다 — 조용히 빠지지 않는다.
		return switch (dessertItem.getItemType()) {
			case CONVENIENCE -> toBrandBranchResponses(dessertItem,
				brand -> convenienceStoreRepository.findBranchesNearby(brand, latitude, longitude, radius, limit));
			case CHAIN -> toBrandBranchResponses(dessertItem,
				brand -> chainStoreRepository.findBranchesNearby(brand, latitude, longitude, radius, limit));
			case GENERIC -> toStoreResponses(dessertItem, dessertItemRepository
				.findSellingStoresNearby(dessertItem.getId(), latitude, longitude, radius, limit));
		};
	}

	/**
	 * 이 디저트를 파는 곳 중 지도 영역(사각형) 안, 기준점(lat·lng)에서 가까운 순. 갈래는 {@link #getSellingStores} 와 같다.
	 *
	 * @param size 없으면 {@link #DEFAULT_BOUNDS_STORE_SIZE}, 최대 {@link #MAX_BOUNDS_STORE_SIZE}
	 */
	public List<DessertStoreResponse> getSellingStoresInBounds(
			Long dessertItemId,
			MapBounds bounds,
			double latitude,
			double longitude,
			Integer size) {
		validateCoordinate(latitude, longitude);
		DessertItem dessertItem = dessertItemRepository.findById(dessertItemId)
			.orElseThrow(() -> new DessertException(DessertErrorCode.DESSERT_NOT_FOUND));

		int requested = size == null ? DEFAULT_BOUNDS_STORE_SIZE : size;
		PageRequest limit = PageRequest.of(0, Math.min(Math.max(requested, 1), MAX_BOUNDS_STORE_SIZE));

		return switch (dessertItem.getItemType()) {
			case CONVENIENCE -> toBrandBranchResponses(dessertItem,
				brand -> convenienceStoreRepository.findBranchesInBounds(brand, bounds.swLat(), bounds.swLng(),
					bounds.neLat(), bounds.neLng(), latitude, longitude, limit));
			case CHAIN -> toBrandBranchResponses(dessertItem,
				brand -> chainStoreRepository.findBranchesInBounds(brand, bounds.swLat(), bounds.swLng(),
					bounds.neLat(), bounds.neLng(), latitude, longitude, limit));
			case GENERIC -> toStoreResponses(dessertItem, dessertItemRepository.findSellingStoresInBounds(
				dessertItem.getId(), bounds.swLat(), bounds.swLng(), bounds.neLat(), bounds.neLng(),
				latitude, longitude, limit));
		};
	}

	/**
	 * 브랜드 이름으로 가까운 지점을 찾는다. 편의점이든 체인이든 하는 일이 같아 표만 갈아 끼운다.
	 */
	private List<DessertStoreResponse> toBrandBranchResponses(
			DessertItem dessertItem,
			Function<String, List<DessertStoreProjection>> branchesOfBrand) {
		// 표의 CHECK 제약(item_type = 'GENERIC' OR brand IS NOT NULL)이 막고 있지만,
		// 브랜드 없이 들어오면 조회할 대상 자체가 없어 조용히 빈 목록을 주면 안 된다.
		if (dessertItem.getBrand() == null) {
			throw new DessertException(DessertErrorCode.DESSERT_BRAND_MISSING);
		}
		return branchesOfBrand.apply(dessertItem.getBrand()).stream()
			.map(branch -> DessertStoreResponse.of(branch, null, dessertItem.getBrand()))
			.toList();
	}

	private List<DessertStoreResponse> toStoreResponses(
			DessertItem dessertItem,
			List<DessertStoreProjection> sellingStores) {
		Map<Long, String> thumbnailByStoreId = thumbnailsOf(sellingStores);
		return sellingStores.stream()
			.map(sellingStore -> DessertStoreResponse.of(
				sellingStore,
				thumbnailByStoreId.get(sellingStore.getStoreId()),
				dessertItem.getBrand()))
			.toList();
	}

	/** 가게 수만큼 쿼리가 늘면 안 된다 — 한 번에 읽어 id 로 나눠 준다 */
	private Map<Long, String> thumbnailsOf(List<DessertStoreProjection> sellingStores) {
		List<Long> storeIds = sellingStores.stream()
			.map(DessertStoreProjection::getStoreId)
			.distinct()
			.toList();
		if (storeIds.isEmpty()) {
			return Map.of();
		}
		return storeImageFileRepository.findThumbnailsByStoreIdIn(storeIds).stream()
			.filter(thumbnail -> thumbnail.getResizedKey320() != null || thumbnail.getAwsKey() != null)
			.collect(Collectors.toMap(
				StoreImageThumbnailProjection::getStoreId,
				thumbnail -> thumbnail.getResizedKey320() != null
					? thumbnail.getResizedKey320()
					: thumbnail.getAwsKey(),
				(first, second) -> first));
	}

	private static int chipOrderOf(DessertItem dessertItem) {
		DessertTrendStatus trendStatus = dessertItem.getTrendStatus();
		return trendStatus == null ? UNJUDGED_CHIP_ORDER : trendStatus.getChipOrder();
	}

	/** 범위 밖 좌표는 PostGIS 가 500 을 낸다 — 400 으로 먼저 거른다(MapBounds 와 같은 규칙) */
	private static void validateCoordinate(double latitude, double longitude) {
		if (!(latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180)) {
			throw new IllegalArgumentException("lat 은 -90~90, lng 는 -180~180 이어야 합니다");
		}
	}
}

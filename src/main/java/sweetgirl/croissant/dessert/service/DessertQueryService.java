package sweetgirl.croissant.dessert.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
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

	/** 반경 기본값. "내 주변"이라 부를 수 있는 한계이자, GIST 인덱스를 태우는 조건이다 */
	public static final double DEFAULT_RADIUS_METERS = 30_000;

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
		DessertItem dessertItem = dessertItemRepository.findById(dessertItemId)
			.orElseThrow(() -> new DessertException(DessertErrorCode.DESSERT_NOT_FOUND));

		PageRequest limit = PageRequest.of(0, Math.min(Math.max(size, 1), MAX_STORE_SIZE));
		double radius = radiusMeters == null ? DEFAULT_RADIUS_METERS : radiusMeters;

		// enum 을 다 훑는 switch 라, 네 번째 성격이 생기면 여기서 컴파일이 깨진다 — 조용히 빠지지 않는다.
		return switch (dessertItem.getItemType()) {
			case CONVENIENCE ->
				toBrandBranchResponses(dessertItem, convenienceStoreRepository::findBranchesNearby,
					latitude, longitude, radius, limit);
			case CHAIN ->
				toBrandBranchResponses(dessertItem, chainStoreRepository::findBranchesNearby,
					latitude, longitude, radius, limit);
			case GENERIC -> toStoreResponses(dessertItem, latitude, longitude, radius, limit);
		};
	}

	/**
	 * 브랜드 이름으로 가까운 지점을 찾는다. 편의점이든 체인이든 하는 일이 같아 표만 갈아 끼운다.
	 */
	private List<DessertStoreResponse> toBrandBranchResponses(
			DessertItem dessertItem,
			BrandBranchQuery brandBranchQuery,
			double latitude,
			double longitude,
			double radius,
			PageRequest limit) {
		// 표의 CHECK 제약(item_type = 'GENERIC' OR brand IS NOT NULL)이 막고 있지만,
		// 브랜드 없이 들어오면 조회할 대상 자체가 없어 조용히 빈 목록을 주면 안 된다.
		if (dessertItem.getBrand() == null) {
			throw new DessertException(DessertErrorCode.DESSERT_BRAND_MISSING);
		}
		return brandBranchQuery.find(dessertItem.getBrand(), latitude, longitude, radius, limit).stream()
			.map(branch -> DessertStoreResponse.of(branch, null, dessertItem.getBrand()))
			.toList();
	}

	/** convenience_store·chain_store 두 리포지토리가 같은 모양이라 함수로 받는다 */
	@FunctionalInterface
	private interface BrandBranchQuery {
		List<DessertStoreProjection> find(String brand, double latitude, double longitude, double radiusMeters,
				PageRequest limit);
	}

	private List<DessertStoreResponse> toStoreResponses(
			DessertItem dessertItem,
			double latitude,
			double longitude,
			double radius,
			PageRequest limit) {
		List<DessertStoreProjection> sellingStores = dessertItemRepository
			.findSellingStoresNearby(dessertItem.getId(), latitude, longitude, radius, limit);
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
}

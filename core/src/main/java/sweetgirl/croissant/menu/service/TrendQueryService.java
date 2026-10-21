package sweetgirl.croissant.menu.service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.external.kakao.client.KakaoGeoClient;
import sweetgirl.croissant.external.kakao.dto.KakaoCoordToAddressResponse;
import sweetgirl.croissant.external.kakao.dto.KakaoCoordToRegionResponse;
import sweetgirl.croissant.menu.code.MenuErrorCode;
import sweetgirl.croissant.menu.dto.projection.MenuImageByStoreProjection;
import sweetgirl.croissant.menu.dto.response.RegionTrendResponse;
import sweetgirl.croissant.menu.entity.FamousMenuByRegion;
import sweetgirl.croissant.menu.entity.NationwideFamousMenu;
import sweetgirl.croissant.menu.enums.MenuCategory;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.FamousMenuByRegionRepository;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.NationwideFamousMenuRepository;
import sweetgirl.croissant.store.dto.projection.HotStoreRankingTrendProjection;
import sweetgirl.croissant.store.dto.projection.StoreDistanceProjection;
import sweetgirl.croissant.store.dto.projection.StoreImageThumbnailProjection;
import sweetgirl.croissant.store.entity.NationwideHotStoreRanking;
import sweetgirl.croissant.store.enums.Region3rd;
import sweetgirl.croissant.store.repository.HotStoreRankingRepository;
import sweetgirl.croissant.store.repository.NationwideHotStoreRankingRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TrendQueryService {

	private final KakaoGeoClient kakaoGeoClient;
	private final StoreRepository storeRepository;
	private final FamousMenuByRegionRepository famousMenuByRegionRepository;
	private final HotStoreRankingRepository hotStoreRankingRepository;
	private final NationwideFamousMenuRepository nationwideFamousMenuRepository;
	private final NationwideHotStoreRankingRepository nationwideHotStoreRankingRepository;
	private final StoreImageFileRepository storeImageFileRepository;
	private final MenuImageFileRepository menuImageFileRepository;

	private static final List<String> DONG_SUFFIXES = Arrays.asList("읍", "면", "동", "구");
	private static final int MAX_STORES_PER_CATEGORY = 30;

	public RegionTrendResponse getTrend(double latitude, double longitude) {
		Region3rd region3rd;

		try {
			region3rd = resolveRegion3rd(latitude, longitude);
		} catch (IllegalStateException e) {
			return new RegionTrendResponse(null, List.of());
		}

		return buildTrendResponse(region3rd, latitude, longitude);
	}

	// lat/lng는 선택 — 있으면 각 빵집까지의 거리를 응답에 포함한다
	public RegionTrendResponse getTrendByRegion(String region, Double latitude, Double longitude) {
		// 구버전 앱은 시도 없는 옛 이름("중구_전체")을 보낸다 — fromRequest가 흡수한다.
		Region3rd region3rd = Region3rd.fromRequest(region);
		if (region3rd == null) {
			throw new MenuException(MenuErrorCode.INVALID_REGION);
		}
		return buildTrendResponse(region3rd, latitude, longitude);
	}

	// 전국 단위 유명 메뉴 & 핫 빵집 랭킹 조회 (지역과 달리 위치/지역 입력 없이 전국 전용 테이블에서 조회)
	public RegionTrendResponse getNationwideTrend() {
		// 전국 유명 메뉴(카테고리) 전체 조회 — 비어 있으면 빈 응답 반환
		List<NationwideFamousMenu> famousMenus = nationwideFamousMenuRepository.findAll();
		if (famousMenus.isEmpty()) {
			return new RegionTrendResponse("전국", List.of());
		}

		// 유명 메뉴 ID들로 핫 빵집 랭킹을 한 번에 조회한 뒤, 메뉴 ID 기준으로 그룹화
		List<Long> famousMenuIds = famousMenus.stream().map(NationwideFamousMenu::getId).toList();
		Map<Long, List<NationwideHotStoreRanking>> rankingsByFamousMenuId = nationwideHotStoreRankingRepository
			.findByNationwideFamousMenuIdInWithStore(famousMenuIds)
			.stream()
			.collect(Collectors.groupingBy(r -> r.getNationwideFamousMenu().getId()));

		// 카테고리 순서를 유지하며 각 카테고리에 속한 랭킹 빵집들을 공통 조립용 구조(CategoryStores)로 변환
		List<CategoryStores> categories = famousMenus.stream()
			.map(menu -> new CategoryStores(
				menu.getMenuCategory().name(),
				rankingsByFamousMenuId.getOrDefault(menu.getId(), List.of())
					.stream()
					.limit(MAX_STORES_PER_CATEGORY)
					.map(r -> new StoreRow(
						r.getStore().getId(),
						r.getStore().getName(),
						r.getStore().getAddress(),
						r.getStore().getRegion1st(),
						r.getStore().getRegion2nd(),
						r.getRank()))
					.toList()))
			.toList();

		// 지역 트렌드와 동일한 조립 로직 재사용 (지역명 자리에는 "전국", 거리 없음)
		return buildResponse("전국", categories, null, null);
	}

	private RegionTrendResponse buildTrendResponse(Region3rd region3rd, Double latitude, Double longitude) {
		List<FamousMenuByRegion> famousMenus = famousMenuByRegionRepository.findByRegion3rd(region3rd);
		if (famousMenus.isEmpty()) {
			return new RegionTrendResponse(region3rd.name(), List.of());
		}

		List<Long> famousMenuIds = famousMenus.stream().map(FamousMenuByRegion::getId).toList();
		Map<Long, List<HotStoreRankingTrendProjection>> rankingsByFamousMenuId = hotStoreRankingRepository
			.findTrendByFamousMenuByRegionIdIn(famousMenuIds)
			.stream()
			.collect(Collectors.groupingBy(HotStoreRankingTrendProjection::getFamousMenuByRegionId));

		List<CategoryStores> categories = famousMenus.stream()
			.map(fmr -> new CategoryStores(
				fmr.getMenuCategory().name(),
				rankingsByFamousMenuId.getOrDefault(fmr.getId(), List.of())
					.stream()
					.limit(MAX_STORES_PER_CATEGORY)
					.map(r -> new StoreRow(
						r.getStoreId(),
						r.getStoreName(),
						r.getStoreAddress(),
						r.getStoreRegion1st(),
						r.getStoreRegion2nd(),
						r.getRank()))
					.toList()))
			.toList();

		// 형식이 "구_동"과 "시도_구_동" 두 가지라 인덱스로 자르면 안 된다.
		String rawName = region3rd.leafName();
		String name = DONG_SUFFIXES.stream()
			.filter(rawName::endsWith)
			.findFirst()
			.map(suffix -> rawName.substring(0, rawName.length() - suffix.length()))
			.orElse(rawName);

		return buildResponse(name, categories, latitude, longitude);
	}

	// 지역/전국 공통 응답 조립 헬퍼: 카테고리별 빵집 목록에 썸네일·거리를 붙여 RegionTrendResponse로 변환
	// 썸네일은 폴백 체인으로 결정: 해당 카테고리 메뉴 사진 → 아무 메뉴 사진 → 가게 사진 → 없음(앱이 placeholder 표시)
	// 거리는 좌표(lat/lng)가 주어졌을 때만 계산해 포함한다
	private RegionTrendResponse buildResponse(
			String regionName, List<CategoryStores> categories, Double latitude, Double longitude) {
		// 모든 카테고리의 빵집 ID를 평탄화·중복 제거하여 썸네일을 한 번에 조회 (N+1 방지)
		List<Long> storeIds = categories.stream()
			.flatMap(c -> c.stores().stream())
			.map(StoreRow::storeId)
			.distinct()
			.toList();

		// storeId → 메뉴 이미지 목록 (세분류 포함, displayOrder 순) — 폴백 체인 1·2단계용
		Map<Long, List<MenuImageByStoreProjection>> menuImagesByStoreId = storeIds.isEmpty()
			? Map.of()
			: menuImageFileRepository.findMenuImagesByStoreIdIn(storeIds)
				.stream()
				.collect(Collectors.groupingBy(MenuImageByStoreProjection::getStoreId));

		// storeId → 가게 이미지 키 목록 — 폴백 체인 3단계용
		Map<Long, List<String>> storeImagesByStoreId = storeIds.isEmpty()
			? Map.of()
			: storeImageFileRepository.findThumbnailsByStoreIdIn(storeIds)
				.stream()
				.collect(Collectors.groupingBy(
					StoreImageThumbnailProjection::getStoreId,
					Collectors.mapping(p -> p.getResizedKey320() != null ? p.getResizedKey320() : p.getAwsKey(),
						Collectors.toList())));

		// storeId → 사용자 위치 기준 거리(m) — 좌표가 주어졌을 때만 일괄 계산
		Map<Long, Double> distanceByStoreId = (latitude == null || longitude == null || storeIds.isEmpty())
			? Map.of()
			: storeRepository.findDistancesByStoreIdIn(storeIds, longitude, latitude)
				.stream()
				.collect(Collectors.toMap(
					StoreDistanceProjection::getId,
					StoreDistanceProjection::getDistanceMeters));

		// 카테고리별로 응답 DTO 조립 + 각 빵집에 썸네일 주입, 빵집 없는 카테고리는 제외
		List<RegionTrendResponse.CategoryTrend> categoryTrends = categories.stream()
			.map(c -> {
				MenuCategory category = parseCategory(c.menuCategory());
				return new RegionTrendResponse.CategoryTrend(
					c.menuCategory(),
					c.stores().stream()
						.map(s -> new RegionTrendResponse.HotStore(
							s.storeId(),
							s.storeName(),
							s.storeAddress(),
							s.storeRegion1st(),
							s.storeRegion2nd(),
							s.rank(),
							resolveThumbnailKeys(category, s.storeId(), menuImagesByStoreId, storeImagesByStoreId),
							distanceByStoreId.get(s.storeId())))
						.toList());
			})
			.filter(ct -> !ct.stores().isEmpty())
			.toList();

		return new RegionTrendResponse(regionName, categoryTrends);
	}

	// 랭킹 카테고리명(enum name) → MenuCategory. 알 수 없는 값이면 null (카테고리 매칭 단계만 건너뜀)
	private MenuCategory parseCategory(String menuCategoryName) {
		try {
			return MenuCategory.valueOf(menuCategoryName);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	// 썸네일 폴백 체인: ① 해당 카테고리 메뉴 사진 → ② 아무 메뉴 사진 → ③ 가게 사진 → ④ 없음
	private List<String> resolveThumbnailKeys(
			MenuCategory category,
			Long storeId,
			Map<Long, List<MenuImageByStoreProjection>> menuImagesByStoreId,
			Map<Long, List<String>> storeImagesByStoreId) {
		List<MenuImageByStoreProjection> menuImages = menuImagesByStoreId.getOrDefault(storeId, List.of());

		if (category != null) {
			for (MenuImageByStoreProjection image : menuImages) {
				if (category.matches(image.getMenuKind())) {
					return List.of(imageKey(image));
				}
			}
		}
		if (!menuImages.isEmpty()) {
			return List.of(imageKey(menuImages.get(0)));
		}

		List<String> storeImages = storeImagesByStoreId.getOrDefault(storeId, List.of());
		return storeImages.isEmpty() ? List.of() : List.of(storeImages.get(0));
	}

	private String imageKey(MenuImageByStoreProjection image) {
		return image.getResizedKey320() != null ? image.getResizedKey320() : image.getAwsKey();
	}

	private record CategoryStores(String menuCategory, List<StoreRow> stores) {
	}

	private record StoreRow(
			Long storeId, String storeName, String storeAddress, String storeRegion1st, String storeRegion2nd,
			int rank) {
	}

	private Region3rd resolveRegion3rd(double latitude, double longitude) {
		String region1st = null;
		String region2nd = null;

		try {
			// 카카오api overlimit 핸들링 필요
			KakaoCoordToAddressResponse addressResponse = kakaoGeoClient.coordToAddress(longitude, latitude);
			KakaoCoordToRegionResponse regionResponse = kakaoGeoClient.coordToRegion(longitude, latitude);

			if (addressResponse != null && !addressResponse.documents().isEmpty()) {
				KakaoCoordToAddressResponse.Document addrDoc = addressResponse.documents().get(0); // 서울 강남구 역삼동
				if (addrDoc.address() != null) {
					String[] parts = addrDoc.address().addressName().split(" ");
					if (parts.length >= 2) {
						region1st = parts[0];
						region2nd = parts[1];
					}
				}
			}

			// B타입(법정동) 먼저 시도, enum 매핑 실패 시 H타입(행정동)으로 fallback
			if (regionResponse != null && !regionResponse.documents().isEmpty() && region2nd != null) {
				for (String typePreference : List.of("B", "H")) {
					String region3rd = regionResponse.documents().stream()
						.filter(d -> typePreference.equals(d.regionType()))
						.map(d -> normalizeRegion3rd(d.region3depthName()))
						.filter(name -> name != null && !name.isBlank() && endsWithDongSuffix(name))
						.findFirst()
						.orElse(null);

					if (region3rd != null) {
						// 이름이 겹치는 구(중구 등)는 시도까지 있어야 가려진다.
						Region3rd matched = Region3rd.of(region1st, region2nd, region3rd);
						if (matched != null) {
							return matched;
						}
						// 이 타입으로 매핑 실패 → 다음 타입 시도
					}
				}
			}
		} catch (Exception ignored) {
			// ignored
		}

		// 최종 Fallback: 가장 가까운 빵집의 동으로 매핑
		List<Object[]> nearest = storeRepository.findNearestRegion(longitude, latitude);
		if (nearest != null && !nearest.isEmpty()) {
			Object[] row = nearest.get(0);
			Region3rd matched = Region3rd.of(
				row[0] == null ? null : row[0].toString(),
				row[1] == null ? null : row[1].toString(),
				row[2] == null ? null : row[2].toString());
			if (matched != null) {
				return matched;
			}
		}

		throw new IllegalStateException("위치에 해당하는 지역을 찾을 수 없습니다.");
	}

	private boolean endsWithDongSuffix(String name) {
		return DONG_SUFFIXES.stream().anyMatch(name::endsWith);
	}

	// "상계6.7동" → "상계동", "수성동4가" → "수성동", "가양1동" → "가양동"
	private String normalizeRegion3rd(String name) {
		if (name == null) {
			return null;
		}
		String normalized = name.replaceAll("\\d+가$", ""); // 수성동4가 → 수성동
		return normalized.replaceAll("[\\d.]+(?=[읍면동구])", ""); // 상계6.7동 → 상계동
	}
}

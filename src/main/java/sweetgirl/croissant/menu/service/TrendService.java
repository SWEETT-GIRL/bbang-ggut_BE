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
import sweetgirl.croissant.menu.dto.response.RegionTrendResponse;
import sweetgirl.croissant.menu.entity.FamousMenuByRegion;
import sweetgirl.croissant.menu.entity.NationwideFamousMenu;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.FamousMenuByRegionRepository;
import sweetgirl.croissant.menu.repository.NationwideFamousMenuRepository;
import sweetgirl.croissant.store.dto.projection.HotStoreRankingTrendProjection;
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
public class TrendService {

	private final KakaoGeoClient kakaoGeoClient;
	private final StoreRepository storeRepository;
	private final FamousMenuByRegionRepository famousMenuByRegionRepository;
	private final HotStoreRankingRepository hotStoreRankingRepository;
	private final NationwideFamousMenuRepository nationwideFamousMenuRepository;
	private final NationwideHotStoreRankingRepository nationwideHotStoreRankingRepository;
	private final StoreImageFileRepository storeImageFileRepository;

	private static final List<String> DONG_SUFFIXES = Arrays.asList("읍", "면", "동", "구");

	public RegionTrendResponse getTrend(double latitude, double longitude) {
		Region3rd region3rd;

		try {
			region3rd = resolveRegion3rd(latitude, longitude);
		} catch (IllegalStateException e) {
			return new RegionTrendResponse(null, List.of());
		}

		return buildTrendResponse(region3rd);
	}

	public RegionTrendResponse getTrendByRegion(String region) {
		Region3rd region3rd;
		try {
			region3rd = Region3rd.valueOf(region);
		} catch (IllegalArgumentException e) {
			throw new MenuException(MenuErrorCode.INVALID_REGION);
		}
		return buildTrendResponse(region3rd);
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
					.map(r -> new StoreRow(
						r.getStore().getId(),
						r.getStore().getName(),
						r.getStore().getAddress(),
						r.getRank()))
					.toList()))
			.toList();

		// 지역 트렌드와 동일한 조립 로직 재사용 (지역명 자리에는 "전국")
		return buildResponse("전국", categories);
	}

	private RegionTrendResponse buildTrendResponse(Region3rd region3rd) {
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
					.map(r -> new StoreRow(
						r.getStoreId(),
						r.getStoreName(),
						r.getStoreAddress(),
						r.getRank()))
					.toList()))
			.toList();

		String rawName = region3rd.name().split("_")[1];
		String name = DONG_SUFFIXES.stream()
			.filter(rawName::endsWith)
			.findFirst()
			.map(suffix -> rawName.substring(0, rawName.length() - suffix.length()))
			.orElse(rawName);

		return buildResponse(name, categories);
	}

	// 지역/전국 공통 응답 조립 헬퍼: 카테고리별 빵집 목록에 썸네일을 붙여 RegionTrendResponse로 변환
	private RegionTrendResponse buildResponse(String regionName, List<CategoryStores> categories) {
		// 모든 카테고리의 빵집 ID를 평탄화·중복 제거하여 썸네일을 한 번에 조회 (N+1 방지)
		List<Long> storeIds = categories.stream()
			.flatMap(c -> c.stores().stream())
			.map(StoreRow::storeId)
			.distinct()
			.toList();

		// storeId → 썸네일 이미지 키 목록 매핑 (빵집이 하나도 없으면 조회 생략)
		Map<Long, List<String>> imagesByStoreId = storeIds.isEmpty()
			? Map.of()
			: storeImageFileRepository.findThumbnailsByStoreIdIn(storeIds)
				.stream()
				.collect(Collectors.groupingBy(
					StoreImageThumbnailProjection::getStoreId,
					Collectors.mapping(StoreImageThumbnailProjection::getAwsKey, Collectors.toList())));

		// 카테고리별로 응답 DTO 조립 + 각 빵집에 썸네일 주입, 빵집 없는 카테고리는 제외
		List<RegionTrendResponse.CategoryTrend> categoryTrends = categories.stream()
			.map(c -> new RegionTrendResponse.CategoryTrend(
				c.menuCategory(),
				c.stores().stream()
					.map(s -> new RegionTrendResponse.HotStore(
						s.storeId(),
						s.storeName(),
						s.storeAddress(),
						s.rank(),
						imagesByStoreId.getOrDefault(s.storeId(), List.of())))
					.toList()))
			.filter(ct -> !ct.stores().isEmpty())
			.toList();

		return new RegionTrendResponse(regionName, categoryTrends);
	}

	private record CategoryStores(String menuCategory, List<StoreRow> stores) {
	}

	private record StoreRow(Long storeId, String storeName, String storeAddress, int rank) {
	}

	private Region3rd resolveRegion3rd(double latitude, double longitude) {
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
						String enumKey = region2nd.trim() + "_" + region3rd.trim();
						try {
							return Region3rd.valueOf(enumKey);
						} catch (IllegalArgumentException ignored) {
							// 이 타입으로 enum 매핑 실패 → 다음 타입 시도
						}
					}
				}
			}
		} catch (Exception ignored) {
			// ignored
		}

		// 최종 Fallback: 가장 가까운 빵집의 동으로 매핑
		List<Object[]> nearest = storeRepository.findNearestRegion(longitude, latitude);
		if (nearest != null && !nearest.isEmpty()) {
			try {
				return Region3rd.valueOf(nearest.get(0)[0] + "_" + nearest.get(0)[1]);
			} catch (IllegalArgumentException ignored) {
				// ignored
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

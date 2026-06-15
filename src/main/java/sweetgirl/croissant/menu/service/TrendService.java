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
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.FamousMenuByRegionRepository;
import sweetgirl.croissant.store.dto.projection.HotStoreRankingTrendProjection;
import sweetgirl.croissant.store.dto.projection.StoreImageThumbnailProjection;
import sweetgirl.croissant.store.enums.Region3rd;
import sweetgirl.croissant.store.repository.HotStoreRankingRepository;
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

	private RegionTrendResponse buildTrendResponse(Region3rd region3rd) {
		List<FamousMenuByRegion> famousMenus = famousMenuByRegionRepository.findByRegion3rd(region3rd);
		if (famousMenus.isEmpty()) {
			return new RegionTrendResponse(region3rd.name(), List.of());
		}

		List<Long> famousMenuIds = famousMenus.stream().map(FamousMenuByRegion::getId).toList();
		List<HotStoreRankingTrendProjection> rankings = hotStoreRankingRepository.findTrendByFamousMenuByRegionIdIn(
			famousMenuIds);

		List<Long> storeIds = rankings.stream().map(HotStoreRankingTrendProjection::getStoreId).distinct().toList();
		Map<Long, List<String>> imagesByStoreId = storeImageFileRepository.findThumbnailsByStoreIdIn(storeIds)
			.stream()
			.collect(Collectors.groupingBy(
				StoreImageThumbnailProjection::getStoreId,
				Collectors.mapping(StoreImageThumbnailProjection::getAwsKey, Collectors.toList())));

		Map<Long, List<HotStoreRankingTrendProjection>> rankingsByFamousMenuId = rankings.stream()
			.collect(Collectors.groupingBy(HotStoreRankingTrendProjection::getFamousMenuByRegionId));

		List<RegionTrendResponse.CategoryTrend> categories = famousMenus.stream()
			.map(fmr -> {
				List<RegionTrendResponse.HotStore> stores = rankingsByFamousMenuId
					.getOrDefault(fmr.getId(), List.of())
					.stream()
					.map(r -> new RegionTrendResponse.HotStore(
						r.getStoreId(),
						r.getStoreName(),
						r.getStoreAddress(),
						r.getRank(),
						imagesByStoreId.getOrDefault(r.getStoreId(), List.of())))
					.toList();
				return new RegionTrendResponse.CategoryTrend(fmr.getMenuCategory().name(), stores);
			})
			.filter(ct -> !ct.stores().isEmpty())
			.toList();

		String rawName = region3rd.name().split("_")[1];
		String name = DONG_SUFFIXES.stream()
			.filter(rawName::endsWith)
			.findFirst()
			.map(suffix -> rawName.substring(0, rawName.length() - suffix.length()))
			.orElse(rawName);

		return new RegionTrendResponse(name, categories);
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

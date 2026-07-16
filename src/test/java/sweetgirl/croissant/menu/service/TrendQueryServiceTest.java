package sweetgirl.croissant.menu.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.external.kakao.client.KakaoGeoClient;
import sweetgirl.croissant.external.kakao.dto.KakaoCoordToAddressResponse;
import sweetgirl.croissant.external.kakao.dto.KakaoCoordToRegionResponse;
import sweetgirl.croissant.menu.dto.projection.StoreMenuThumbnailProjection;
import sweetgirl.croissant.menu.dto.response.RegionTrendResponse;
import sweetgirl.croissant.menu.entity.FamousMenuByRegion;
import sweetgirl.croissant.menu.enums.MenuCategory;
import sweetgirl.croissant.menu.enums.MenuKind;
import sweetgirl.croissant.menu.repository.FamousMenuByRegionRepository;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.store.dto.projection.HotStoreRankingTrendProjection;
import sweetgirl.croissant.store.dto.projection.StoreDistanceProjection;
import sweetgirl.croissant.store.dto.projection.StoreImageThumbnailProjection;
import sweetgirl.croissant.store.enums.Region3rd;
import sweetgirl.croissant.store.repository.HotStoreRankingRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreRepository;

@ExtendWith(MockitoExtension.class)
class TrendQueryServiceTest {

	@Mock
	private KakaoGeoClient kakaoGeoClient;
	@Mock
	private StoreRepository storeRepository;
	@Mock
	private FamousMenuByRegionRepository famousMenuByRegionRepository;
	@Mock
	private HotStoreRankingRepository hotStoreRankingRepository;
	@Mock
	private StoreImageFileRepository storeImageFileRepository;
	@Mock
	private MenuImageFileRepository menuImageFileRepository;

	@InjectMocks
	private TrendQueryService trendQueryService;

	private static final double LAT = 37.5779093;
	private static final double LNG = 126.9862841;

	@Test
	@DisplayName("B타입(법정동) enum 매핑 성공 시 해당 지역 반환")
	void getTrend_returnsMappedRegion_whenBTypeMatches() {
		// given
		given(kakaoGeoClient.coordToAddress(LNG, LAT)).willReturn(addressResponse("서울 종로구 가회동"));
		given(kakaoGeoClient.coordToRegion(LNG, LAT)).willReturn(regionResponse("B", "가회동", "H", "가회동"));
		given(famousMenuByRegionRepository.findByRegion3rd(Region3rd.종로구_가회동)).willReturn(List.of());

		// when
		RegionTrendResponse response = trendQueryService.getTrend(LAT, LNG);

		// then
		assertThat(response.region3rd()).isEqualTo(Region3rd.종로구_가회동.name());
		verify(storeRepository, never()).findNearestRegion(anyDouble(), anyDouble());
	}

	@Test
	@DisplayName("B타입 enum 매핑 실패 시 H타입(행정동)으로 fallback")
	void getTrend_returnsHTypeRegion_whenBTypeFails() {
		// given: 재동은 enum 없음, 가회동은 enum 있음
		given(kakaoGeoClient.coordToAddress(LNG, LAT)).willReturn(addressResponse("서울 종로구 재동 84-18"));
		given(kakaoGeoClient.coordToRegion(LNG, LAT)).willReturn(regionResponse("B", "재동", "H", "가회동"));
		given(famousMenuByRegionRepository.findByRegion3rd(Region3rd.종로구_가회동)).willReturn(List.of());

		// when
		RegionTrendResponse response = trendQueryService.getTrend(LAT, LNG);

		// then
		assertThat(response.region3rd()).isEqualTo(Region3rd.종로구_가회동.name());
		verify(storeRepository, never()).findNearestRegion(anyDouble(), anyDouble());
	}

	@Test
	@DisplayName("B/H 모두 enum 매핑 실패 시 PostGIS로 가장 가까운 빵집 동으로 fallback")
	void getTrend_returnsNearestRegion_whenBothTypesFail() {
		// given: 재동, 존재하지않는동 모두 enum 없음
		given(kakaoGeoClient.coordToAddress(LNG, LAT)).willReturn(addressResponse("서울 종로구 재동 84-18"));
		given(kakaoGeoClient.coordToRegion(LNG, LAT)).willReturn(regionResponse("B", "재동", "H", "존재하지않는동"));
		given(storeRepository.findNearestRegion(LNG, LAT))
			.willReturn(List.<Object[]>of(new Object[] {"종로구", "가회동"}));
		given(famousMenuByRegionRepository.findByRegion3rd(Region3rd.종로구_가회동)).willReturn(List.of());

		// when
		RegionTrendResponse response = trendQueryService.getTrend(LAT, LNG);

		// then
		assertThat(response.region3rd()).isEqualTo(Region3rd.종로구_가회동.name());
		verify(storeRepository).findNearestRegion(LNG, LAT);
	}

	@Test
	@DisplayName("카카오 API 예외 발생 시 구 제한 없이 가장 가까운 빵집 동으로 fallback")
	void getTrend_returnsNearestRegion_whenKakaoApiFails() {
		// given
		given(kakaoGeoClient.coordToAddress(anyDouble(), anyDouble()))
			.willThrow(new RuntimeException("Kakao API 장애"));
		given(storeRepository.findNearestRegion(LNG, LAT))
			.willReturn(List.<Object[]>of(new Object[] {"종로구", "가회동"}));
		given(famousMenuByRegionRepository.findByRegion3rd(Region3rd.종로구_가회동)).willReturn(List.of());

		// when
		RegionTrendResponse response = trendQueryService.getTrend(LAT, LNG);

		// then
		assertThat(response.region3rd()).isEqualTo(Region3rd.종로구_가회동.name());
		verify(storeRepository).findNearestRegion(LNG, LAT);
	}

	@Test
	@DisplayName("모든 fallback 실패 시 null 지역명과 빈 카테고리 반환")
	void getTrend_returnsNullRegion_whenAllFallbacksFail() {
		// given
		given(kakaoGeoClient.coordToAddress(anyDouble(), anyDouble()))
			.willThrow(new RuntimeException("Kakao API 장애"));
		given(storeRepository.findNearestRegion(LNG, LAT)).willReturn(List.of());

		// when
		RegionTrendResponse response = trendQueryService.getTrend(LAT, LNG);

		// then
		assertThat(response.region3rd()).isNull();
		assertThat(response.categories()).isEmpty();
	}

	@Test
	@DisplayName("랭킹 카테고리에 해당하는 메뉴가 있으면 그 메뉴 사진을 반환")
	void getTrendByRegion_returnsMatchingMenuImages() {
		// given: 소금빵 랭킹, 가게10은 크루아상·소금빵 메뉴 보유
		givenSaltCategoryRankingWithStore(10L);
		StoreMenuThumbnailProjection croissant = menuImage(10L, 200L, MenuKind.크루아상, "croissant.jpg");
		StoreMenuThumbnailProjection salt = menuImage(10L, 100L, MenuKind.소금빵, "salt.jpg");
		given(menuImageFileRepository.findStoreMenuThumbnailsByStoreIdIn(anyList()))
			.willReturn(List.of(croissant, salt));

		// when
		RegionTrendResponse response = trendQueryService.getTrendByRegion("종로구_가회동", null, null);

		// then: 소금빵 카테고리 → 소금빵 메뉴 사진 우선, 메뉴 사진 있으므로 가게 사진은 조회하지 않음
		assertThat(firstStoreKeys(response)).containsExactly("salt.jpg");
		verify(storeImageFileRepository, never()).findThumbnailsByStoreIdIn(anyList());
	}

	@Test
	@DisplayName("카테고리에 해당하는 메뉴가 없으면 다른(대표) 메뉴 사진을 반환")
	void getTrendByRegion_fallsBackToAnyMenuImages() {
		// given: 소금빵 랭킹이지만 가게10은 소금빵 메뉴가 없음(크루아상·베이글)
		givenSaltCategoryRankingWithStore(10L);
		StoreMenuThumbnailProjection croissant = menuImage(10L, 200L, MenuKind.크루아상, "croissant.jpg");
		StoreMenuThumbnailProjection bagel = menuImage(10L, 300L, MenuKind.베이글, "bagel.jpg");
		given(menuImageFileRepository.findStoreMenuThumbnailsByStoreIdIn(anyList()))
			.willReturn(List.of(croissant, bagel));

		// when
		RegionTrendResponse response = trendQueryService.getTrendByRegion("종로구_가회동", null, null);

		// then: 매칭 메뉴 없음 → 대표(첫) 메뉴 사진, 메뉴 사진 있으므로 가게 사진은 조회하지 않음
		assertThat(firstStoreKeys(response)).containsExactly("croissant.jpg");
		verify(storeImageFileRepository, never()).findThumbnailsByStoreIdIn(anyList());
	}

	@Test
	@DisplayName("메뉴 사진이 하나도 없으면 가게 사진을 반환")
	void getTrendByRegion_fallsBackToStoreImages() {
		// given: 소금빵 랭킹, 가게10은 메뉴 사진 없음
		givenSaltCategoryRankingWithStore(10L);
		StoreImageThumbnailProjection storeImage = storeImage(10L, "store.jpg");
		given(storeImageFileRepository.findThumbnailsByStoreIdIn(anyList())).willReturn(List.of(storeImage));
		given(menuImageFileRepository.findStoreMenuThumbnailsByStoreIdIn(anyList())).willReturn(List.of());

		// when
		RegionTrendResponse response = trendQueryService.getTrendByRegion("종로구_가회동", null, null);

		// then: 메뉴 사진 없음 → 가게 사진 fallback
		assertThat(firstStoreKeys(response)).containsExactly("store.jpg");
	}

	@Test
	@DisplayName("위/경도가 있으면 각 빵집까지의 거리를 문자열로 반환한다 (1km 미만은 m)")
	void getTrendByRegion_returnsDistanceInMeters() {
		// given
		givenSaltCategoryRankingWithStore(10L);
		StoreDistanceProjection distance = distanceProjection(10L, 320.4);
		given(storeRepository.findDistanceMetersByIdIn(anyList(), anyDouble(), anyDouble()))
			.willReturn(List.of(distance));

		// when
		RegionTrendResponse response = trendQueryService.getTrendByRegion("종로구_가회동", 37.5, 127.0);

		// then: 320.4m → 반올림 320m
		assertThat(firstStore(response).distance()).isEqualTo("320m");
	}

	@Test
	@DisplayName("거리가 1km 이상이면 km 단위로 반환한다")
	void getTrendByRegion_returnsDistanceInKilometers() {
		// given
		givenSaltCategoryRankingWithStore(10L);
		StoreDistanceProjection distance = distanceProjection(10L, 1540.0);
		given(storeRepository.findDistanceMetersByIdIn(anyList(), anyDouble(), anyDouble()))
			.willReturn(List.of(distance));

		// when
		RegionTrendResponse response = trendQueryService.getTrendByRegion("종로구_가회동", 37.5, 127.0);

		// then: 1540m → 1.5km
		assertThat(firstStore(response).distance()).isEqualTo("1.5km");
	}

	@Test
	@DisplayName("위/경도가 없으면 거리는 null이고 거리 조회를 하지 않는다")
	void getTrendByRegion_returnsNullDistance_whenNoLocation() {
		// given
		givenSaltCategoryRankingWithStore(10L);

		// when
		RegionTrendResponse response = trendQueryService.getTrendByRegion("종로구_가회동", null, null);

		// then
		assertThat(firstStore(response).distance()).isNull();
		verify(storeRepository, never()).findDistanceMetersByIdIn(anyList(), anyDouble(), anyDouble());
	}

	private RegionTrendResponse.HotStore firstStore(RegionTrendResponse response) {
		return response.categories().get(0).stores().get(0);
	}

	private StoreDistanceProjection distanceProjection(Long storeId, double meters) {
		StoreDistanceProjection projection = mock(StoreDistanceProjection.class);
		given(projection.getStoreId()).willReturn(storeId);
		given(projection.getDistanceMeters()).willReturn(meters);
		return projection;
	}

	// 소금빵 카테고리 랭킹 + 지정 가게 1곳을 stubbing (지역=종로구_가회동)
	private void givenSaltCategoryRankingWithStore(Long storeId) {
		FamousMenuByRegion famousMenu = mock(FamousMenuByRegion.class);
		given(famousMenu.getId()).willReturn(1L);
		given(famousMenu.getMenuCategory()).willReturn(MenuCategory.소금빵);
		HotStoreRankingTrendProjection rankingRow = ranking(1L, storeId, 1);
		given(famousMenuByRegionRepository.findByRegion3rd(Region3rd.종로구_가회동))
			.willReturn(List.of(famousMenu));
		given(hotStoreRankingRepository.findTrendByFamousMenuByRegionIdIn(List.of(1L)))
			.willReturn(List.of(rankingRow));
	}

	private List<String> firstStoreKeys(RegionTrendResponse response) {
		return response.categories().get(0).stores().get(0).thumbnailImageKeys();
	}

	private HotStoreRankingTrendProjection ranking(Long famousMenuId, Long storeId, int rank) {
		HotStoreRankingTrendProjection projection = mock(HotStoreRankingTrendProjection.class);
		given(projection.getFamousMenuByRegionId()).willReturn(famousMenuId);
		given(projection.getStoreId()).willReturn(storeId);
		given(projection.getRank()).willReturn(rank);
		given(projection.getStoreName()).willReturn("가게" + storeId);
		given(projection.getStoreAddress()).willReturn("서울 종로구 가회동");
		given(projection.getStoreRegion1st()).willReturn("서울특별시");
		given(projection.getStoreRegion2nd()).willReturn("종로구");
		return projection;
	}

	private StoreMenuThumbnailProjection menuImage(Long storeId, Long menuId, MenuKind menuKind, String key) {
		StoreMenuThumbnailProjection projection = mock(StoreMenuThumbnailProjection.class);
		given(projection.getStoreId()).willReturn(storeId);
		given(projection.getMenuId()).willReturn(menuId);
		given(projection.getMenuKind()).willReturn(menuKind);
		given(projection.getResizedKey320()).willReturn(key);
		return projection;
	}

	private StoreImageThumbnailProjection storeImage(Long storeId, String key) {
		StoreImageThumbnailProjection projection = mock(StoreImageThumbnailProjection.class);
		given(projection.getStoreId()).willReturn(storeId);
		given(projection.getResizedKey320()).willReturn(key);
		return projection;
	}

	private KakaoCoordToAddressResponse addressResponse(String addressName) {
		return new KakaoCoordToAddressResponse(List.of(
			new KakaoCoordToAddressResponse.Document(
				new KakaoCoordToAddressResponse.RoadAddress(""),
				new KakaoCoordToAddressResponse.Address(addressName, "", ""))));
	}

	private KakaoCoordToRegionResponse regionResponse(
			String type1, String dong1, String type2, String dong2) {
		return new KakaoCoordToRegionResponse(List.of(
			new KakaoCoordToRegionResponse.Document(type1, "종로구", dong1),
			new KakaoCoordToRegionResponse.Document(type2, "종로구", dong2)));
	}
}

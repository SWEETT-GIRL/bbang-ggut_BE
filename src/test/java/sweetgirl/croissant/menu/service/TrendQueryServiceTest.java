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
import sweetgirl.croissant.menu.dto.projection.MenuImageByStoreProjection;
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
			.willReturn(List.<Object[]>of(new Object[] {"서울", "종로구", "가회동"}));
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
			.willReturn(List.<Object[]>of(new Object[] {"서울", "종로구", "가회동"}));
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
	@DisplayName("트렌드 썸네일 폴백 체인: 카테고리 메뉴 사진 → 아무 메뉴 사진 → 가게 사진 → 없음")
	void getTrendByRegion_resolvesThumbnailFallbackChain() {
		// given: 케이크 카테고리 랭킹에 4개 빵집
		FamousMenuByRegion famousMenu = mock(FamousMenuByRegion.class);
		given(famousMenu.getId()).willReturn(1L);
		given(famousMenu.getMenuCategory()).willReturn(MenuCategory.케이크);

		// 행 mock들을 먼저 만들어 두고(중첩 스터빙 방지) 리포지토리를 스터빙한다
		HotStoreRankingTrendProjection rank1 = rankingRow(10L, 1); // 케이크 메뉴 사진 보유 → 카테고리 일치 사진
		HotStoreRankingTrendProjection rank2 = rankingRow(20L, 2); // 식빵 메뉴 사진만 보유 → 아무 메뉴 사진
		HotStoreRankingTrendProjection rank3 = rankingRow(30L, 3); // 메뉴 사진 없음, 가게 사진 보유 → 가게 사진
		HotStoreRankingTrendProjection rank4 = rankingRow(40L, 4); // 아무 사진도 없음 → 빈 목록
		// 10번 가게는 식빵 사진이 먼저 오지만 카테고리(케이크) 일치 사진이 우선돼야 한다
		MenuImageByStoreProjection bread10 = menuImageRow(10L, MenuKind.식빵, "menu-bread-10");
		MenuImageByStoreProjection cake10 = menuImageRow(10L, MenuKind.케이크, "menu-cake-10");
		MenuImageByStoreProjection bread20 = menuImageRow(20L, MenuKind.식빵, "menu-bread-20");
		StoreImageThumbnailProjection store30 = storeImageRow(30L, "store-30");

		given(famousMenuByRegionRepository.findByRegion3rd(Region3rd.종로구_가회동))
			.willReturn(List.of(famousMenu));
		given(hotStoreRankingRepository.findTrendByFamousMenuByRegionIdIn(List.of(1L)))
			.willReturn(List.of(rank1, rank2, rank3, rank4));
		given(menuImageFileRepository.findMenuImagesByStoreIdIn(anyList()))
			.willReturn(List.of(bread10, cake10, bread20));
		given(storeImageFileRepository.findThumbnailsByStoreIdIn(anyList()))
			.willReturn(List.of(store30));

		// when: 좌표 없이 조회 — 거리는 null이어야 한다
		RegionTrendResponse response = trendQueryService.getTrendByRegion(Region3rd.종로구_가회동.name(), null, null);

		// then
		List<RegionTrendResponse.HotStore> stores = response.categories().get(0).stores();
		assertThat(stores.get(0).thumbnailImageKeys()).containsExactly("menu-cake-10");
		assertThat(stores.get(1).thumbnailImageKeys()).containsExactly("menu-bread-20");
		assertThat(stores.get(2).thumbnailImageKeys()).containsExactly("store-30");
		assertThat(stores.get(3).thumbnailImageKeys()).isEmpty();
		assertThat(stores).allSatisfy(s -> assertThat(s.distanceMeter()).isNull());
		verify(storeRepository, never()).findDistancesByStoreIdIn(anyList(), anyDouble(), anyDouble());
	}

	@Test
	@DisplayName("좌표를 주면 가게별 거리(distanceMeter)를 포함해 반환")
	void getTrendByRegion_includesDistances_whenCoordsProvided() {
		// given
		FamousMenuByRegion famousMenu = mock(FamousMenuByRegion.class);
		given(famousMenu.getId()).willReturn(1L);
		given(famousMenu.getMenuCategory()).willReturn(MenuCategory.케이크);
		HotStoreRankingTrendProjection rank1 = rankingRow(10L, 1);
		HotStoreRankingTrendProjection rank2 = rankingRow(20L, 2);
		StoreDistanceProjection dist10 = distanceRow(10L, 152.3);

		given(famousMenuByRegionRepository.findByRegion3rd(Region3rd.종로구_가회동))
			.willReturn(List.of(famousMenu));
		given(hotStoreRankingRepository.findTrendByFamousMenuByRegionIdIn(List.of(1L)))
			.willReturn(List.of(rank1, rank2));
		given(menuImageFileRepository.findMenuImagesByStoreIdIn(anyList())).willReturn(List.of());
		given(storeImageFileRepository.findThumbnailsByStoreIdIn(anyList())).willReturn(List.of());
		given(storeRepository.findDistancesByStoreIdIn(anyList(), eq(LNG), eq(LAT)))
			.willReturn(List.of(dist10));

		// when
		RegionTrendResponse response = trendQueryService.getTrendByRegion(Region3rd.종로구_가회동.name(), LAT, LNG);

		// then: 거리 조회 결과에 있는 가게만 값, 없으면 null
		List<RegionTrendResponse.HotStore> stores = response.categories().get(0).stores();
		assertThat(stores.get(0).distanceMeter()).isEqualTo(152.3);
		assertThat(stores.get(1).distanceMeter()).isNull();
	}

	private StoreDistanceProjection distanceRow(Long storeId, double meters) {
		StoreDistanceProjection row = mock(StoreDistanceProjection.class);
		given(row.getId()).willReturn(storeId);
		given(row.getDistanceMeters()).willReturn(meters);
		return row;
	}

	private HotStoreRankingTrendProjection rankingRow(Long storeId, int rank) {
		HotStoreRankingTrendProjection row = mock(HotStoreRankingTrendProjection.class);
		given(row.getFamousMenuByRegionId()).willReturn(1L);
		given(row.getStoreId()).willReturn(storeId);
		given(row.getRank()).willReturn(rank);
		return row;
	}

	private MenuImageByStoreProjection menuImageRow(Long storeId, MenuKind kind, String key) {
		MenuImageByStoreProjection row = mock(MenuImageByStoreProjection.class);
		given(row.getStoreId()).willReturn(storeId);
		lenient().when(row.getMenuKind()).thenReturn(kind);
		lenient().when(row.getResizedKey320()).thenReturn(key);
		return row;
	}

	private StoreImageThumbnailProjection storeImageRow(Long storeId, String key) {
		StoreImageThumbnailProjection row = mock(StoreImageThumbnailProjection.class);
		given(row.getStoreId()).willReturn(storeId);
		given(row.getResizedKey320()).willReturn(key);
		return row;
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

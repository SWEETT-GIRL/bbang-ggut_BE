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
import sweetgirl.croissant.menu.dto.response.RegionTrendResponse;
import sweetgirl.croissant.menu.repository.FamousMenuByRegionRepository;
import sweetgirl.croissant.store.enums.Region3rd;
import sweetgirl.croissant.store.repository.HotStoreRankingRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreRepository;

@ExtendWith(MockitoExtension.class)
class TrendServiceTest {

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

	@InjectMocks
	private TrendService trendService;

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
		RegionTrendResponse response = trendService.getTrend(LAT, LNG);

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
		RegionTrendResponse response = trendService.getTrend(LAT, LNG);

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
		RegionTrendResponse response = trendService.getTrend(LAT, LNG);

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
		RegionTrendResponse response = trendService.getTrend(LAT, LNG);

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
		RegionTrendResponse response = trendService.getTrend(LAT, LNG);

		// then
		assertThat(response.region3rd()).isNull();
		assertThat(response.categories()).isEmpty();
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

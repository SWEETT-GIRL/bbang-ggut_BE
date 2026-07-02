package sweetgirl.croissant.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.external.kakao.client.KakaoLocalClient;
import sweetgirl.croissant.external.kakao.dto.KakaoPlace;
import sweetgirl.croissant.store.dto.response.AddressSearchResult;

@ExtendWith(MockitoExtension.class)
class StoreReportQueryServiceTest {

	@Mock
	KakaoLocalClient kakaoLocalClient;

	@InjectMocks
	StoreReportQueryService storeReportQueryService;

	@Test
	@DisplayName("searchAddress: 카카오 검색 결과를 AddressSearchResult로 매핑해 반환한다")
	void searchAddress_mapsKakaoPlaceToResult() {
		KakaoPlace place = new KakaoPlace(
			"1",
			"파티세리영원",
			"서울 마포구 숭문4길 6",
			"서울 마포구 숭문4길 6",
			"음식점 > 카페 > 제과,베이커리",
			"FD6",
			"02-0000-0000",
			"126.94569719881",
			"37.547596273407");
		given(kakaoLocalClient.searchByKeywordForAddress("파티세리영원")).willReturn(List.of(place));

		List<AddressSearchResult> result = storeReportQueryService.searchAddress("파티세리영원");

		assertThat(result).hasSize(1);
		assertThat(result.get(0).placeName()).isEqualTo("파티세리영원");
		assertThat(result.get(0).address()).isEqualTo("서울 마포구 숭문4길 6");
		assertThat(result.get(0).latitude()).isEqualTo(37.547596273407);
		assertThat(result.get(0).longitude()).isEqualTo(126.94569719881);
	}

	@Test
	@DisplayName("searchAddress: 결과가 없으면 빈 리스트를 반환한다")
	void searchAddress_returnsEmptyList_whenNoResults() {
		given(kakaoLocalClient.searchByKeywordForAddress("없는가게")).willReturn(List.of());

		List<AddressSearchResult> result = storeReportQueryService.searchAddress("없는가게");

		assertThat(result).isEmpty();
	}
}

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

import sweetgirl.croissant.external.naver.client.NaverLocalSearchClient;
import sweetgirl.croissant.store.dto.response.NaverPlaceSearchResult;

@ExtendWith(MockitoExtension.class)
class StoreAddReportQueryServiceTest {

	@Mock
	NaverLocalSearchClient naverLocalSearchClient;

	@InjectMocks
	StoreAddReportQueryService storeAddReportQueryService;

	@Test
	@DisplayName("searchNaverPlaces: 네이버 검색 결과를 그대로 반환한다")
	void searchNaverPlaces_returnsClientResults() {
		NaverPlaceSearchResult place = new NaverPlaceSearchResult(
			"1234567",
			"파티세리영원",
			"음식점>카페>제과,베이커리",
			"02-0000-0000",
			"서울 마포구 숭문4길 6",
			"서울 마포구 숭문4길 6",
			37.547596273407,
			126.94569719881,
			"https://map.naver.com/p/place/1234567");
		given(naverLocalSearchClient.search("파티세리영원")).willReturn(List.of(place));

		List<NaverPlaceSearchResult> result = storeAddReportQueryService.searchNaverPlaces("파티세리영원");

		assertThat(result).hasSize(1);
		assertThat(result.get(0).naverPlaceId()).isEqualTo("1234567");
		assertThat(result.get(0).name()).isEqualTo("파티세리영원");
	}

	@Test
	@DisplayName("searchNaverPlaces: 결과가 없으면 빈 리스트를 반환한다")
	void searchNaverPlaces_returnsEmptyList_whenNoResults() {
		given(naverLocalSearchClient.search("없는가게")).willReturn(List.of());

		List<NaverPlaceSearchResult> result = storeAddReportQueryService.searchNaverPlaces("없는가게");

		assertThat(result).isEmpty();
	}
}

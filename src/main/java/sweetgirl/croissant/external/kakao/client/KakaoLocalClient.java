package sweetgirl.croissant.external.kakao.client;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.common.enums.District;
import sweetgirl.croissant.external.kakao.dto.KakaoPlace;
import sweetgirl.croissant.external.kakao.dto.KakaoRegionResponse;
import sweetgirl.croissant.external.kakao.dto.KakaoSearchResponse;
import sweetgirl.croissant.external.kakao.dto.KakaoSearchResult;
import sweetgirl.croissant.external.kakao.enums.KakaoCategoryCode;
import sweetgirl.croissant.external.kakao.exception.KakaoErrorCode;
import sweetgirl.croissant.external.kakao.exception.KakaoLocalApiException;

@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoLocalClient {

	private static final String KEYWORD_SEARCH_PATH = "/v2/local/search/keyword.json";
	private static final String COORD_TO_REGION_PATH = "/v2/local/geo/coord2regioncode.json";
	private static final int MAX_SIZE = 15;

	private final RestClient kakaoRestClient;

	public KakaoSearchResult searchByKeyword(String query, int page) {
		KakaoSearchResponse response;
		try {
			response = kakaoRestClient.get()
				.uri(uriBuilder -> uriBuilder
					.path(KEYWORD_SEARCH_PATH)
					.queryParam("query", query)
					.queryParam("size", MAX_SIZE)
					.queryParam("page", page)
					.build())
				.retrieve()
				.body(KakaoSearchResponse.class);
		} catch (RestClientException e) {
			throw new KakaoLocalApiException(KakaoErrorCode.KEYWORD_SEARCH_ERROR, e);
		}

		if (response == null) {
			return new KakaoSearchResult(List.of(), true);
		}

		List<KakaoPlace> places = response.documents() == null ? List.of()
			: response.documents().stream()
				.filter(this::isBreadPlace)
				.toList();

		boolean isEnd = response.meta() == null || response.meta().isEnd();
		return new KakaoSearchResult(places, isEnd);
	}

	public District getDistrict(String longitude, String latitude) {
		try {
			KakaoRegionResponse response = kakaoRestClient.get()
				.uri(uriBuilder -> uriBuilder
					.path(COORD_TO_REGION_PATH)
					.queryParam("x", longitude)
					.queryParam("y", latitude)
					.build())
				.retrieve()
				.body(KakaoRegionResponse.class);

			if (response == null || response.documents() == null) {
				return null;
			}

			return response.documents().stream()
				.filter(doc -> "B".equals(doc.regionType()))
				.findFirst()
				.flatMap(doc -> District.from(doc.regionDepth1Name(), doc.regionDepth2Name()))
				.orElse(null);
		} catch (RestClientException e) {
			return null;
		}
	}

	private boolean isBreadPlace(KakaoPlace place) {
		return KakaoCategoryCode.from(place.categoryGroupCode())
			.map(code -> code.matches(place.categoryName()))
			.orElse(false);
	}
}

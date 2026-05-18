package sweetgirl.croissant.external.kakao.client;

import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.external.kakao.dto.KakaoPlace;
import sweetgirl.croissant.external.kakao.dto.KakaoSearchResponse;
import sweetgirl.croissant.external.kakao.dto.KakaoSearchResult;
import sweetgirl.croissant.external.kakao.enums.FranchiseName;
import sweetgirl.croissant.external.kakao.enums.KakaoCategoryCode;
import sweetgirl.croissant.external.kakao.exception.KakaoErrorCode;
import sweetgirl.croissant.external.kakao.exception.KakaoLocalApiException;

@Component
@RequiredArgsConstructor
public class KakaoLocalClient {

	private static final String KEYWORD_SEARCH_PATH = "/v2/local/search/keyword.json";
	private static final int MAX_SIZE = 15;

	private final RestClient kakaoRestClient;

	public KakaoSearchResult searchByKeyword(String query, int page) {
		List<KakaoPlace> fd6Places = searchByCategory(query, "FD6", page);
		List<KakaoPlace> ce7Places = searchByCategory(query, "CE7", page);

		List<KakaoPlace> places = Stream.concat(fd6Places.stream(), ce7Places.stream())
			.filter(this::isBreadPlace)
			.filter(place -> !FranchiseName.matches(place.placeName()))
			.distinct()
			.toList();

		boolean isEnd = places.size() < MAX_SIZE;
		return new KakaoSearchResult(places, isEnd);
	}

	private List<KakaoPlace> searchByCategory(String query, String categoryCode, int page) {
		KakaoSearchResponse response;
		try {
			response = kakaoRestClient.get()
				.uri(uriBuilder -> uriBuilder
					.path(KEYWORD_SEARCH_PATH)
					.queryParam("query", query)
					.queryParam("size", MAX_SIZE)
					.queryParam("page", page)
					.queryParam("category_group_code", categoryCode)
					.build())
				.retrieve()
				.body(KakaoSearchResponse.class);
		} catch (RestClientException e) {
			throw new KakaoLocalApiException(KakaoErrorCode.KEYWORD_SEARCH_ERROR, e);
		}

		if (response == null || response.documents() == null) {
			return List.of();
		}
		return response.documents();
	}

	public List<KakaoPlace> searchByKeywordForAddress(String query) {
		KakaoSearchResponse response;
		try {
			response = kakaoRestClient.get()
				.uri(uriBuilder -> uriBuilder
					.path(KEYWORD_SEARCH_PATH)
					.queryParam("query", query)
					.queryParam("size", MAX_SIZE)
					.build())
				.retrieve()
				.body(KakaoSearchResponse.class);
		} catch (RestClientException e) {
			throw new KakaoLocalApiException(KakaoErrorCode.KEYWORD_SEARCH_ERROR, e);
		}

		if (response == null || response.documents() == null) {
			return List.of();
		}
		return response.documents();
	}

	private boolean isBreadPlace(KakaoPlace place) {
		return KakaoCategoryCode.from(place.categoryGroupCode())
			.map(code -> code.matches(place.categoryName()))
			.orElse(false);
	}
}

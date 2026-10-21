package sweetgirl.croissant.external.kakao.client;

import java.util.List;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import sweetgirl.croissant.external.kakao.dto.KakaoPlace;
import sweetgirl.croissant.external.kakao.dto.KakaoSearchResponse;
import sweetgirl.croissant.external.kakao.dto.KakaoSearchResult;
import sweetgirl.croissant.external.kakao.enums.FranchiseName;
import sweetgirl.croissant.external.kakao.enums.KakaoCategoryCode;
import sweetgirl.croissant.external.kakao.enums.KakaoResponseCategoryKeyword;
import sweetgirl.croissant.external.kakao.enums.KakaoResponseExcludeKeyword;
import sweetgirl.croissant.external.kakao.enums.KakaoResponsePriorityKeyword;
import sweetgirl.croissant.external.kakao.exception.KakaoErrorCode;
import sweetgirl.croissant.external.kakao.exception.KakaoLocalApiException;

@Component
public class KakaoLocalClient {

	private static final String KEYWORD_SEARCH_PATH = "/v2/local/search/keyword.json";
	private static final int MAX_SIZE = 15;

	private final RestClient kakaoRestClient;

	public KakaoLocalClient(@Qualifier("kakaoRestClient") RestClient kakaoRestClient) {
		this.kakaoRestClient = kakaoRestClient;
	}

	public KakaoSearchResult searchByKeyword(String query, int page) {
		return searchByKeyword(query, page, false);
	}

	// trustedKeyword=true 일 때는 categoryName 화이트리스트(제과/베이커리/카페 등) 검사를 우회하고
	// CE7/FD6 그룹코드 + Exclude 블랙리스트만 적용. specific 한 검색 키워드(브런치/샌드위치 등)는
	// Kakao search relevance 자체를 신뢰해 "음식점 > 양식" 으로 등록된 브런치 카페(예: 테니) 도 수집.
	public KakaoSearchResult searchByKeyword(String query, int page, boolean trustedKeyword) {
		List<KakaoPlace> fd6Places = searchByCategory(query, "FD6", page);
		List<KakaoPlace> ce7Places = searchByCategory(query, "CE7", page);

		List<KakaoPlace> places = Stream.concat(fd6Places.stream(), ce7Places.stream())
			.filter(place -> trustedKeyword ? isTrustedScope(place) : isBreadPlace(place))
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

	// 신뢰 키워드(브런치/샌드위치 등) 검색용 완화 필터:
	//   - 기본 strict 필터(제과/베이커리/카페 등 화이트리스트) 통과 OR
	//   - categoryName 이 "양식" 으로 끝나는 generic 카테고리 (sub 미분류 케이스, 예: "음식점 > 양식")
	//   "양식 > 피자/파스타/스테이크/햄버거" 처럼 sub 가 명시된 경우는 endsWith 조건 미충족 → 제외 유지.
	private boolean isTrustedScope(KakaoPlace place) {
		if (KakaoCategoryCode.from(place.categoryGroupCode()).isEmpty()) {
			return false;
		}
		String name = place.categoryName();
		if (name == null) {
			return false;
		}
		if (KakaoResponsePriorityKeyword.matches(name)) {
			return true;
		}
		if (KakaoResponseExcludeKeyword.matches(name)) {
			return false;
		}
		if (KakaoResponseCategoryKeyword.matches(name)) {
			return true;
		}
		return name.endsWith("양식");
	}
}

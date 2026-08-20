package sweetgirl.croissant.external.naver.client;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import sweetgirl.croissant.external.naver.dto.NaverLocalSearchResponse;
import sweetgirl.croissant.store.code.StoreReportErrorCode;
import sweetgirl.croissant.store.dto.response.NaverPlaceSearchResult;
import sweetgirl.croissant.store.exception.StoreReportException;

@Component
public class NaverLocalSearchClient {

	private static final String LOCAL_SEARCH_PATH = "/v1/search/local.json";
	/** 네이버 지역검색 display 상한 */
	private static final int MAX_DISPLAY = 5;

	private final String clientId;
	private final String clientSecret;
	private final RestClient restClient;

	public NaverLocalSearchClient(
			@Value("${naver.search.base-url:https://openapi.naver.com}") String baseUrl,
			@Value("${naver.search.client-id:}") String clientId,
			@Value("${naver.search.client-secret:}") String clientSecret) {
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.restClient = RestClient.create(baseUrl);
	}

	public List<NaverPlaceSearchResult> search(String query) {
		if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
			throw new StoreReportException(StoreReportErrorCode.REPORT_NAVER_SEARCH_CONFIG_MISSING);
		}

		NaverLocalSearchResponse response;
		try {
			response = restClient.get()
				.uri(uriBuilder -> uriBuilder
					.path(LOCAL_SEARCH_PATH)
					.queryParam("query", query)
					// 네이버 지역검색은 display 최대 5다. 10을 넣으면 그대로 5가 오거나
					// 파라미터 오류가 되므로 상한을 지킨다.
					.queryParam("display", MAX_DISPLAY)
					// sort 를 넣지 않으면 정확도순(기본)이다.
					// random 은 말 그대로 무작위라, 찾는 가게가 네이버에 있어도 안 뽑힐 수 있다 —
					// "울산 페즈"를 검색했는데 울산 페즈가 안 나온 것이 이 때문이었다.
					.build())
				.header("X-Naver-Client-Id", clientId)
				.header("X-Naver-Client-Secret", clientSecret)
				.retrieve()
				.body(NaverLocalSearchResponse.class);
		} catch (RestClientException e) {
			throw new StoreReportException(StoreReportErrorCode.REPORT_NAVER_SEARCH_FAILED);
		}

		if (response == null || response.items() == null) {
			return List.of();
		}
		return response.items().stream()
			.map(NaverPlaceSearchResult::from)
			.toList();
	}
}

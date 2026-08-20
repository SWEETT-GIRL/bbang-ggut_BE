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
					.queryParam("display", 10)
					.queryParam("sort", "random")
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

package sweetgirl.croissant.external.naver.client;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import sweetgirl.croissant.external.naver.dto.NaverBlogPost;
import sweetgirl.croissant.external.naver.dto.NaverBlogSearchResponse;
import sweetgirl.croissant.external.naver.exception.NaverSearchErrorCode;
import sweetgirl.croissant.external.naver.exception.NaverSearchException;

/**
 * 네이버 블로그 검색. 실패 검색어 판정이 "런베뮤"처럼 지역검색에 장소가 안 나오는 줄임말을 풀 때 근거 글을 모은다.
 *
 * <p>지역검색({@link NaverLocalSearchClient})과 같은 애플리케이션 키·같은 하루 한도(25,000건)를 쓴다.
 */
@Component
public class NaverBlogSearchClient {

	private static final String BLOG_SEARCH_PATH = "/v1/search/blog.json";
	private static final int MAX_DISPLAY = 100;

	private final String clientId;
	private final String clientSecret;
	private final RestClient restClient;

	public NaverBlogSearchClient(
			@Value("${naver.search.base-url:https://openapi.naver.com}") String baseUrl,
			@Value("${naver.search.client-id:}") String clientId,
			@Value("${naver.search.client-secret:}") String clientSecret) {
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.restClient = RestClient.create(baseUrl);
	}

	/**
	 * @param display 가져올 글 수. 네이버 상한 100
	 */
	public List<NaverBlogPost> search(String query, int display) {
		if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
			throw new NaverSearchException(NaverSearchErrorCode.CONFIG_MISSING);
		}

		NaverBlogSearchResponse response;
		try {
			response = restClient.get()
				.uri(uriBuilder -> uriBuilder
					.path(BLOG_SEARCH_PATH)
					.queryParam("query", query)
					.queryParam("display", Math.min(Math.max(display, 1), MAX_DISPLAY))
					// sim = 정확도순. date 는 최신순이라 검색어와 덜 맞는 글이 섞인다
					.queryParam("sort", "sim")
					.build())
				.header("X-Naver-Client-Id", clientId)
				.header("X-Naver-Client-Secret", clientSecret)
				.retrieve()
				.body(NaverBlogSearchResponse.class);
		} catch (HttpClientErrorException e) {
			if (e.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
				throw new NaverSearchException(NaverSearchErrorCode.RATE_LIMITED, e);
			}
			throw new NaverSearchException(NaverSearchErrorCode.SEARCH_FAILED, e);
		} catch (RestClientException e) {
			throw new NaverSearchException(NaverSearchErrorCode.SEARCH_FAILED, e);
		}

		if (response == null || response.items() == null) {
			return List.of();
		}
		return response.items().stream()
			.map(NaverBlogPost::from)
			.toList();
	}
}

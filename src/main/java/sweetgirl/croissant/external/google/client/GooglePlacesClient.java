package sweetgirl.croissant.external.google.client;

import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import sweetgirl.croissant.external.google.dto.GoogleFindPlaceResponse;
import sweetgirl.croissant.external.google.dto.GooglePlaceDetailsResponse;
import sweetgirl.croissant.external.google.exception.GooglePlacesErrorCode;
import sweetgirl.croissant.external.google.exception.GooglePlacesException;

@Component
public class GooglePlacesClient {

	private static final String BASE_URL = "https://places.googleapis.com";
	private static final String TEXT_SEARCH_PATH = "/v1/places:searchText";
	private static final String PLACE_DETAILS_PATH = "/v1/places/{placeId}";

	private final String apiKey;
	private final RestClient restClient;

	public GooglePlacesClient(@Value("${google.places.api-key}") String apiKey) {
		this.apiKey = apiKey;
		this.restClient = RestClient.create(BASE_URL);
	}

	/**
	 * 가게명 + 주소로 Google Place ID를 검색합니다.
	 *
	 * @return place id (결과 없으면 empty)
	 */
	public Optional<String> findPlaceId(String storeName, String address) {
		GoogleFindPlaceResponse response;
		try {
			response = restClient.post()
				.uri(TEXT_SEARCH_PATH)
				.header("X-Goog-Api-Key", apiKey)
				.header("X-Goog-FieldMask", "places.id")
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("textQuery", storeName + " " + address))
				.retrieve()
				.body(GoogleFindPlaceResponse.class);
		} catch (RestClientException e) {
			throw new GooglePlacesException(GooglePlacesErrorCode.FIND_PLACE_ERROR, e);
		}

		if (response == null || response.places() == null || response.places().isEmpty()) {
			return Optional.empty();
		}
		return Optional.ofNullable(response.places().get(0).id());
	}

	/**
	 * Place ID로 영업시간 정보를 조회합니다.
	 *
	 * @return OpeningHours (영업시간 정보 없으면 empty)
	 */
	public Optional<GooglePlaceDetailsResponse.OpeningHours> getOpeningHours(String placeId) {
		GooglePlaceDetailsResponse response;
		try {
			response = restClient.get()
				.uri(uriBuilder -> uriBuilder
					.path(PLACE_DETAILS_PATH)
					.build(placeId))
				.header("X-Goog-Api-Key", apiKey)
				.header("X-Goog-FieldMask", "regularOpeningHours")
				.retrieve()
				.body(GooglePlaceDetailsResponse.class);
		} catch (HttpClientErrorException.NotFound e) {
			return Optional.empty();
		} catch (RestClientException e) {
			throw new GooglePlacesException(GooglePlacesErrorCode.PLACE_DETAILS_ERROR, e);
		}

		if (response == null || response.regularOpeningHours() == null) {
			return Optional.empty();
		}
		return Optional.of(response.regularOpeningHours());
	}
}

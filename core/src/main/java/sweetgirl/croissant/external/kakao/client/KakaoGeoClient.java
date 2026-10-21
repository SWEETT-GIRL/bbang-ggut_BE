package sweetgirl.croissant.external.kakao.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import io.micrometer.core.annotation.Timed;
import sweetgirl.croissant.external.kakao.dto.KakaoCoordToAddressResponse;
import sweetgirl.croissant.external.kakao.dto.KakaoCoordToRegionResponse;

@Component
// 트렌드 조회 요청 처리 중 동기 호출되므로 카카오 지연이 응답 지연으로 직결된다.
@Timed(value = "external.api", extraTags = {"client", "kakao_geo"}, histogram = true)
public class KakaoGeoClient {

	private static final String COORD_TO_ADDRESS_PATH = "/v2/local/geo/coord2address.json";
	private static final String COORD_TO_REGION_PATH = "/v2/local/geo/coord2regioncode.json";

	private final RestClient kakaoRestClient;

	public KakaoGeoClient(@Qualifier("kakaoRestClient") RestClient kakaoRestClient) {
		this.kakaoRestClient = kakaoRestClient;
	}

	// 지번 주소 → region_2depth_name(구/시) 파싱용
	public KakaoCoordToAddressResponse coordToAddress(double longitude, double latitude) {
		return kakaoRestClient.get()
			.uri(uriBuilder -> uriBuilder
				.path(COORD_TO_ADDRESS_PATH)
				.queryParam("x", longitude)
				.queryParam("y", latitude)
				.build())
			.retrieve()
			.body(KakaoCoordToAddressResponse.class);
	}

	// 행정동(H 타입) → region_3depth_name 파싱용
	public KakaoCoordToRegionResponse coordToRegion(double longitude, double latitude) {
		return kakaoRestClient.get()
			.uri(uriBuilder -> uriBuilder
				.path(COORD_TO_REGION_PATH)
				.queryParam("x", longitude)
				.queryParam("y", latitude)
				.build())
			.retrieve()
			.body(KakaoCoordToRegionResponse.class); //KakaoCoordToRegionResponse 형태로 역직렬화해서 반환
	}
}

package sweetgirl.croissant.external.kakao.client;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.external.kakao.dto.KakaoCoordToAddressResponse;
import sweetgirl.croissant.external.kakao.dto.KakaoCoordToRegionResponse;

@Component
@RequiredArgsConstructor
public class KakaoGeoClient {

	private static final String COORD_TO_ADDRESS_PATH = "/v2/local/geo/coord2address.json";
	private static final String COORD_TO_REGION_PATH = "/v2/local/geo/coord2regioncode.json";

	private final RestClient kakaoRestClient;

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

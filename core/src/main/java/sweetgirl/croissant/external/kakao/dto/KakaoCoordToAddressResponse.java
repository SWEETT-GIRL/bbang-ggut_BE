package sweetgirl.croissant.external.kakao.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KakaoCoordToAddressResponse(
		List<Document> documents) {
	public record Document(
			@JsonProperty("road_address") RoadAddress roadAddress,
			@JsonProperty("address") Address address) {
	}

	public record RoadAddress(
			@JsonProperty("region_3depth_name") String region3depthName) {
	}

	public record Address(
			@JsonProperty("address_name") String addressName,
			@JsonProperty("region_2depth_name") String region2depthName,
			@JsonProperty("region_3depth_name") String region3depthName) {
	}
}

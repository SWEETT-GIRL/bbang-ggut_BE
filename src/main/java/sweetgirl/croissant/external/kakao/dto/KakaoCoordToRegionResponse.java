package sweetgirl.croissant.external.kakao.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KakaoCoordToRegionResponse(
		List<Document> documents) {
	public record Document(
			@JsonProperty("region_type") String regionType,
			@JsonProperty("region_2depth_name") String region2depthName,
			@JsonProperty("region_3depth_name") String region3depthName) {
	}
}

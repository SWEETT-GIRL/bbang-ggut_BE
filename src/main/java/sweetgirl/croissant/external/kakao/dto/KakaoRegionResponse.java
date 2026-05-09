package sweetgirl.croissant.external.kakao.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KakaoRegionResponse(List<KakaoRegionDocument> documents) {

	public record KakaoRegionDocument(
			@JsonProperty("region_type") String regionType,
			@JsonProperty("region_1depth_name") String regionDepth1Name,
			@JsonProperty("region_2depth_name") String regionDepth2Name) {
	}
}

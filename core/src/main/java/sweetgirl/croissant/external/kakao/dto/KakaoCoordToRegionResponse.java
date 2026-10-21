package sweetgirl.croissant.external.kakao.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KakaoCoordToRegionResponse(
		List<Document> documents) {
	/** region_type: B = 법정동, H = 행정동. 시·도("서울특별시")·시·군·구("동대문구", "수원시 영통구")는 둘이 같다. */
	public record Document(
			@JsonProperty("region_type") String regionType,
			@JsonProperty("region_1depth_name") String region1depthName,
			@JsonProperty("region_2depth_name") String region2depthName,
			@JsonProperty("region_3depth_name") String region3depthName) {
	}
}

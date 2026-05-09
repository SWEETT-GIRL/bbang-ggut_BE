package sweetgirl.croissant.external.kakao.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KakaoPlace(
		String id,
		@JsonProperty("place_name") String placeName,
		@JsonProperty("address_name") String addressName,
		@JsonProperty("road_address_name") String roadAddressName,
		@JsonProperty("category_name") String categoryName,
		@JsonProperty("category_group_code") String categoryGroupCode,
		String phone,
		String x,
		String y) {
}

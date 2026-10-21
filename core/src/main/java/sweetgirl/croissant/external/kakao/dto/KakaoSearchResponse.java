package sweetgirl.croissant.external.kakao.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KakaoSearchResponse(
		List<KakaoPlace> documents,
		KakaoMeta meta) {

	public record KakaoMeta(
			@JsonProperty("is_end") boolean isEnd) {
	}
}

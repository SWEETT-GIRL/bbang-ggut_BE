package sweetgirl.croissant.external.kakao.dto;

import java.util.List;

public record KakaoSearchResult(
		List<KakaoPlace> places,
		boolean isEnd) {
}

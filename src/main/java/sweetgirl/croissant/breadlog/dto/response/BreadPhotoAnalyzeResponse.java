package sweetgirl.croissant.breadlog.dto.response;

import java.util.List;

public record BreadPhotoAnalyzeResponse(List<PhotoSuggestions> results) {

	/** 사진 하나의 결과. 제안이 없으면 빈 목록 — 실패든 "빵이 없다"든 앱에게는 같다 */
	public record PhotoSuggestions(String photoKey, List<TagSuggestion> suggestions) {
	}

	/**
	 * @param name 메뉴 이름은 모델의 답이 아니라 DB 값이다 — 목록 밖 이름은 애초에 버린다
	 * @param x    0~1. 기존 태그 좌표와 같은 좌표계
	 */
	public record TagSuggestion(Long menuId, String name, double x, double y) {
	}
}

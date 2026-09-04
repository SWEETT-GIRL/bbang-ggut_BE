package sweetgirl.croissant.letter.dto.response;

import java.util.List;

/**
 * 내 편지함. 아직 편지가 오간 적 없으면 빈 목록이다 — 스레드가 없다는 것과 편지가
 * 없다는 것을 앱이 구분할 이유가 없어서 404를 내지 않는다.
 */
public record LetterListResponse(
		List<LetterMessageResponse> messages,
		boolean hasNext) {

	public static LetterListResponse empty() {
		return new LetterListResponse(List.of(), false);
	}
}

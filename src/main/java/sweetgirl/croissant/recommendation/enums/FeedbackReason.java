package sweetgirl.croissant.recommendation.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 👎(DISLIKE) 에 붙는 이유 칩. 무엇이 틀렸는지를 가른다 — BAD_REASON 은 LLM 이유 문장의 품질을 직접 겨냥한다.
 * 칩 목록은 제품이 바뀌면 같이 바뀌므로 DB 에 값 CHECK 를 두지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum FeedbackReason {

	NOT_MY_TASTE("취향이 아니에요"),
	TOO_FAR("너무 멀어요"),
	ALREADY_VISITED("이미 가봤어요"),
	BAD_REASON("이유 문장이 이상해요"),
	/** 코스 4곳이 같은 타입뿐일 때. 다양성 후처리를 넣을지 정하는 관찰용 칩 — 근거와 실측치는 #661 · V20260915001000 참고. */
	TOO_SIMILAR("비슷한 가게뿐이에요"),
	OTHER("기타");

	private final String label;
}

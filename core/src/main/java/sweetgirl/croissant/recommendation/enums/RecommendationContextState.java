package sweetgirl.croissant.recommendation.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 빈 코스 카드가 무엇을 보일지 (설계 §1). 검사는 관문이 아니다 — 신호가 부족할 때만 카드가 검사로 바뀐다. */
@Getter
@RequiredArgsConstructor
public enum RecommendationContextState {

	CHIPS("상권 칩"),
	QUIZ("신호 부족 — 같은 자리에 빵BTI 4문항");

	private final String description;
}

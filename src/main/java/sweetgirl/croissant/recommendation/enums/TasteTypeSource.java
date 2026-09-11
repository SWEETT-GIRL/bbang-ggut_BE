package sweetgirl.croissant.recommendation.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 사용자 타입이 어디서 정해졌는지. 검사 직후엔 검사가 타입을 결정하고, 기록이 쌓이면 신호가 서서히 움직인다. */
@Getter
@RequiredArgsConstructor
public enum TasteTypeSource {

	QUIZ("빵BTI 검사 응답 (4문항 폴백 또는 8문항 MY)"),
	SIGNAL("기록·조회·담기·저장 신호의 가중 평균");

	private final String description;
}

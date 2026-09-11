package sweetgirl.croissant.recommendation.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 추천 진입점 (설계 §1). */
@Getter
@RequiredArgsConstructor
public enum RecommendationMode {

	AREA("빈 코스 화면에서 상권을 골라 받는 코스 추천. 룰이 후보를 뽑고 LLM 이 고르고 이유를 쓴다"),
	ANCHOR("방금 담은 가게 근처 추천. 가게는 룰이 고르고 LLM 은 이유만 쓴다");

	private final String description;
}

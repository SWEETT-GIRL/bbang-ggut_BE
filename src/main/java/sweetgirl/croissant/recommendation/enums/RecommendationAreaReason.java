package sweetgirl.croissant.recommendation.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 상권 칩을 고른 근거 (설계 §5.2). 칩 순서가 곧 우선순위다 — 세션 의도 → 저장·계획 가게 → 취향 점수 → 인기 순. */
@Getter
@RequiredArgsConstructor
public enum RecommendationAreaReason {

	SESSION("이 세션에서 본 상권"),
	SAVED("저장·계획한 가게가 있는 상권"),
	TASTE("취향 점수가 높은 상권"),
	POPULAR("인기 상권");

	private final String description;
}

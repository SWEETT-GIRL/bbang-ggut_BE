package sweetgirl.croissant.recommendation.dto.response;

import java.util.List;

/**
 * 추천 한 묶음의 이유 문장 (#764).
 *
 * @param recommendationId 어느 묶음의 문장인지 — 앱이 카드와 맞춰 끼운다
 * @param provider {@code rule | gemini | openai | anthropic}. rule 이면 아래 문장은 룰이 쓴 것이다
 * @param model 부른 모델. 룰 문장이면 null
 * @param fallback 룰 문장으로 답했는가 — 꺼져 있거나 실패 · 타임아웃 · 하루 상한이면 true. 에러가 아니라 결과다
 * @param latencyMs LLM 을 기다린 시간. 부르지 않았으면 0. 앱이 잰 시간과 견줘 "무엇이 느린가" 를 가른다
 * @param reasons 추천 결과와 같은 순서의 가게별 문장
 */
public record ReasonSentencesResponse(
		Long recommendationId,
		String provider,
		String model,
		boolean fallback,
		long latencyMs,
		List<ReasonSentence> reasons) {
}

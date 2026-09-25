package sweetgirl.croissant.recommendation.service;

import sweetgirl.croissant.recommendation.enums.ExpressionType;

/**
 * 만들어진 이유 문장 하나와 그 출처 (설계 §7.2).
 *
 * <p>응답 DTO {@code dto.response.ReasonSentence}(storeId · storeName · reason)와 <b>다른 것</b>이다 — 이름이
 * 비슷해 헷갈리므로 {@code Generated} 를 붙였다. 이쪽은 내부 결과라 근거와 {@code templateId} 를 들고 다니고,
 * 지표를 셀 때 무엇이 실제로 쓰였는지를 여기서 읽는다.
 *
 * <p>{@code storeId} 를 담는 이유는 매핑 실수를 막기 위해서다. 코스 단위로 만들면 목록 순서로만 가게와 짝지어지는데,
 * 중간에 한 줄이 빠지거나 순서가 바뀌면 <b>다른 가게의 이유가 붙는다</b> — 예외도 안 나고 문장은 그럴듯하다.
 *
 * @param evidence 최종 출력 기준의 근거. 길이 때문에 기본 문장으로 내려갔으면 {@link ReasonEvidence#none()} 이다
 */
public record GeneratedReasonSentence(long storeId, String text, String templateId, ReasonEvidence evidence,
		ExpressionType expression) {
}

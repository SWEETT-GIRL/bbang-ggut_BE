package sweetgirl.croissant.external.gemini.dto;

/**
 * 구조화 출력 호출 결과.
 *
 * @param value           응답 JSON 을 요청한 타입으로 역직렬화한 것
 * @param promptTokens    입력 토큰 — 비용 추적·배치 크기 조정용
 * @param candidatesTokens 출력 토큰
 */
public record GeminiStructuredResult<T>(
		T value,
		int promptTokens,
		int candidatesTokens) {
}

package sweetgirl.croissant.external.llm;

/**
 * 이유 문장을 맡길 수 있는 LLM 벤더 (#662). 값은 {@code recommendation.provider} 컬럼에 그대로 들어간다 — 엔티티 주석의
 * "rule | gemini | openai | anthropic" 과 같은 체계다.
 */
public enum LlmProvider {
	GEMINI,
	OPENAI,
	ANTHROPIC;

	public String code() {
		return name().toLowerCase();
	}
}

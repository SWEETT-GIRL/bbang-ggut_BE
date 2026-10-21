package sweetgirl.croissant.recommendation.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 이유 문장 LLM 의 응답 약속 — 스키마와 파싱 (#662 · #764).
 *
 * <p>실험 API({@link ReasonExperimentService})와 앱이 부르는 서빙 API({@link ReasonSentenceCommandService})가 같은 것을
 * 쓴다. 복제해 두면 실험에서 고른 프롬프트·스키마가 서빙에서는 다른 모양으로 나가, "실험에서 좋았다" 가 근거가 되지 못한다.
 *
 * <p>문장은 표준 JSON 스키마 하나로 나가고, Gemini 용 대문자 번역은 어댑터가 한다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class ReasonLlmContract {

	/** 가게 하나당 문장 하나 — 배열 순서는 믿지 않고 storeId 로 맞춘다 */
	static final Map<String, Object> RESPONSE_SCHEMA = Map.of(
		"type", "object",
		"properties", Map.of(
			"reasons", Map.of(
				"type", "array",
				"items", Map.of(
					"type", "object",
					"properties", Map.of(
						"storeId", Map.of("type", "integer"),
						"reason", Map.of("type", "string")),
					"required", List.of("storeId", "reason"),
					"additionalProperties", false))),
		"required", List.of("reasons"),
		"additionalProperties", false);

	/**
	 * 모델이 순서를 바꾸거나 가게를 빠뜨려도 판이 흐트러지지 않게 가게 id 로 뒤집는다. 같은 가게를 두 번 쓰면 첫 문장만 남긴다 —
	 * 뒤엣것으로 덮으면 우리가 고르지 않은 쪽이 화면에 간다.
	 */
	static Map<Long, String> byStoreId(ReasonAnswer answer) {
		Map<Long, String> byStoreId = new LinkedHashMap<>();
		if (answer == null || answer.reasons() == null) {
			return byStoreId;
		}
		answer.reasons().stream()
			.filter(reason -> reason.storeId() != null && reason.reason() != null)
			.forEach(reason -> byStoreId.putIfAbsent(reason.storeId(), reason.reason().trim()));
		return byStoreId;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record ReasonAnswer(List<Reason> reasons) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Reason(Long storeId, String reason) {
	}
}

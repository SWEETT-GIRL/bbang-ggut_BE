package sweetgirl.croissant.external.gemini.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 표준 JSON 스키마를 Gemini 가 받는 OpenAPI 부분집합으로 옮긴다 (#662).
 *
 * <p>다른 둘(Anthropic · OpenAI)은 표준 JSON 스키마를 그대로 먹는데 Gemini 만 두 가지가 다르다 —
 * {@code type} 이 대문자여야 하고({@code "string"} → {@code "STRING"}), {@code additionalProperties}
 * 같은 검증 키워드를 아예 받지 않아 400 이 난다. 실험이 스키마 하나를 세 벤더에 돌려야 하므로 그 차이를 여기
 * 한 곳에서만 흡수한다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class GeminiSchemaTranslator {

	/** Gemini OpenAPI 부분집합이 이해하는 키만 남긴다. 나머지는 통째로 버린다 */
	private static final Set<String> SUPPORTED_KEYS = Set.of(
		"type", "format", "description", "nullable", "enum",
		"items", "properties", "required", "propertyOrdering",
		"minItems", "maxItems", "minimum", "maximum");

	static Map<String, Object> translate(Map<String, Object> schema) {
		Map<String, Object> translated = new LinkedHashMap<>();
		schema.forEach((key, value) -> {
			if (!SUPPORTED_KEYS.contains(key)) {
				return;
			}
			translated.put(key, translateValue(key, value));
		});
		return translated;
	}

	private static Object translateValue(String key, Object value) {
		if ("type".equals(key) && value instanceof String type) {
			return type.toUpperCase(Locale.ROOT);
		}
		if ("properties".equals(key) && value instanceof Map<?, ?> properties) {
			Map<String, Object> translated = new LinkedHashMap<>();
			properties.forEach((name, child) -> translated.put(String.valueOf(name), translateChild(child)));
			return translated;
		}
		if ("items".equals(key)) {
			return translateChild(value);
		}
		return value;
	}

	@SuppressWarnings("unchecked")
	private static Object translateChild(Object child) {
		if (child instanceof Map<?, ?> map) {
			return translate((Map<String, Object>)map);
		}
		if (child instanceof List<?> list) {
			List<Object> translated = new ArrayList<>(list.size());
			list.forEach(element -> translated.add(translateChild(element)));
			return translated;
		}
		return child;
	}
}

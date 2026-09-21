package sweetgirl.croissant.external.llm.dto;

import java.util.Locale;

import sweetgirl.croissant.external.llm.LlmProvider;

/**
 * 설정 · 요청에 적는 {@code 벤더:모델} 한 줄 (#662 · #764).
 *
 * <p>같은 표기를 실험 요청도 쓰고 서빙 설정도 써서 한 곳에 둔다. 잘못된 표기는 {@link IllegalArgumentException} 으로
 * 알리고, 그것을 400 으로 볼지 기동 실패로 볼지는 부르는 쪽이 정한다 — 사용자가 보낸 값과 우리가 적어 둔 값은 다른 잘못이다.
 */
public record LlmModelRef(LlmProvider provider, String model) {

	private static final String SEPARATOR = ":";

	public static LlmModelRef parse(String value) {
		String[] parts = value == null ? new String[0] : value.split(SEPARATOR, 2);
		if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
			throw new IllegalArgumentException("모델 표기는 '벤더:모델' 형식이어야 합니다: " + value);
		}
		LlmProvider provider;
		try {
			provider = LlmProvider.valueOf(parts[0].trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("모르는 벤더입니다: " + parts[0], e);
		}
		return new LlmModelRef(provider, parts[1].trim());
	}
}

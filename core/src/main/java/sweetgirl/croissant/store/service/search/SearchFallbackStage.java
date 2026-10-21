package sweetgirl.croissant.store.service.search;

import java.util.Locale;

/**
 * 0건 뒤에 결과를 낸 폴백이 어느 단계였는지. {@link SearchOutcome#FALLBACK} 하나로 묶인 결과를 추적 로그·지표에서 가르는 값이다.
 *
 * <p>Prometheus {@code stage} 태그 값으로 노출되므로 값의 개수를 고정된 소수로 유지한다.
 */
public enum SearchFallbackStage {

	/** 폴백 없이 1차(주소 부분일치 포함)에서 끝났다. */
	NONE,

	/** 붙여 쓴 토큰을 지역·빵 단어로 분해해 다시 검색했다. */
	GLUED_TOKEN,

	/** 지역으로 분류된 토큰까지 이름 토큰으로 보고, 모든 토큰이 가게 이름(구명)에 든 가게를 찾았다. */
	NAME_ANY_ORDER,

	/** 가장 구체적인 토큰 하나만 남기고 나머지를 버렸다. */
	SINGLE_TOKEN;

	public String tagValue() {
		return name().toLowerCase(Locale.ROOT);
	}
}

package sweetgirl.croissant.store.service.search;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 검색어·색인 텍스트 정규화 — 소문자화 후 한글·영문·숫자만 남긴다(공백·특수문자 제거).
 *
 * <p>DB 생성 컬럼(store.name_normalized 등, `regexp_replace(lower(x), '[^0-9a-z가-힣]', '', 'g')`)과
 * 같은 규칙이어야 검색어와 색인 텍스트의 비교가 성립한다.
 */
public final class SearchTextNormalizer {

	private static final Pattern NON_SEARCHABLE = Pattern.compile("[^0-9a-z가-힣]");

	private SearchTextNormalizer() {
		// 정적 유틸리티
	}

	public static String normalize(String text) {
		if (text == null) {
			return "";
		}
		return NON_SEARCHABLE.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("");
	}
}

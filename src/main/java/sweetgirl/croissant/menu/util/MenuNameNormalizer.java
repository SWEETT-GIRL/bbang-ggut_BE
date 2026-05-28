package sweetgirl.croissant.menu.util;

import java.util.regex.Pattern;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MenuNameNormalizer {

	private static final Pattern WHITESPACE = Pattern.compile("\\s+");
	// 한글/영문/숫자만 남기고 나머지 제거.
	private static final Pattern NON_WORD = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}가-힣]");

	public static String normalize(String name) {
		if (name == null) {
			return "";
		}
		String stripped = WHITESPACE.matcher(name).replaceAll("");
		String wordOnly = NON_WORD.matcher(stripped).replaceAll("");
		return wordOnly.toLowerCase();
	}
}

package sweetgirl.croissant.menu.util;

import java.util.Locale;
import java.util.regex.Pattern;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MenuNameNormalizer {

	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	public static String normalize(String name) {
		if (name == null) {
			return "";
		}
		return WHITESPACE.matcher(name).replaceAll("").toLowerCase(Locale.ROOT);
	}
}

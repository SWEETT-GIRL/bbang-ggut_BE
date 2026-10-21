package sweetgirl.croissant.external.kakao.enums;

import java.util.Arrays;

public enum KakaoResponsePriorityKeyword {
	디저트카페;

	public static boolean matches(String categoryName) {
		return Arrays.stream(values()).anyMatch(kw -> categoryName.contains(kw.name()));
	}
}

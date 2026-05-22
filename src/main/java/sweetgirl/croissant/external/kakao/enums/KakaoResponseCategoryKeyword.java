package sweetgirl.croissant.external.kakao.enums;

import java.util.Arrays;

public enum KakaoResponseCategoryKeyword {
	제과, 베이커리, 샌드위치, 간식, 디저트, 카페;

	public static boolean matches(String categoryName) {
		return Arrays.stream(values()).anyMatch(kw -> categoryName.contains(kw.name()));
	}
}

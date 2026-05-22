package sweetgirl.croissant.external.kakao.enums;

import java.util.Arrays;

public enum KakaoResponseExcludeKeyword {
	과일, 생과일, 다방, 라이브, 고양이, 강아지, 애견, 사주, 테마카페, 가정생활("가정,생활");

	private final String keyword;

	KakaoResponseExcludeKeyword() {
		this.keyword = name();
	}

	KakaoResponseExcludeKeyword(String keyword) {
		this.keyword = keyword;
	}

	public static boolean matches(String categoryName) {
		return Arrays.stream(values()).anyMatch(kw -> categoryName.contains(kw.keyword));
	}
}

package sweetgirl.croissant.external.kakao.enums;

import java.util.Arrays;
import java.util.Optional;

public enum KakaoCategoryCode {
	CE7, FD6;

	public boolean matches(String categoryName) {
		if (KakaoResponsePriorityKeyword.matches(categoryName)) {
			return true;
		}
		if (KakaoResponseExcludeKeyword.matches(categoryName)) {
			return false;
		}
		return KakaoResponseCategoryKeyword.matches(categoryName);
	}

	public static Optional<KakaoCategoryCode> from(String code) {
		return Arrays.stream(values())
			.filter(c -> c.name().equals(code))
			.findFirst();
	}
}

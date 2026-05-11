package sweetgirl.croissant.external.kakao.enums;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public enum KakaoCategoryCode {
	CE7(List.of()),
	FD6(Arrays.stream(StoreCategoryKeyword.values()).map(Enum::name).toList());

	private final List<String> allowedKeywords;

	KakaoCategoryCode(List<String> allowedKeywords) {
		this.allowedKeywords = allowedKeywords;
	}

	public boolean matches(String categoryName) {
		if (allowedKeywords.isEmpty()) {
			return true;
		}
		return allowedKeywords.stream().anyMatch(categoryName::contains);
	}

	public static Optional<KakaoCategoryCode> from(String code) {
		return Arrays.stream(values())
			.filter(c -> c.name().equals(code))
			.findFirst();
	}
}

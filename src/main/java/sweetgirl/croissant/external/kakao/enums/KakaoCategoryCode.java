package sweetgirl.croissant.external.kakao.enums;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public enum KakaoCategoryCode {
	CE7(List.of(), Arrays.stream(CafeExcludeKeyword.values()).map(CafeExcludeKeyword::keyword).toList()),
	FD6(Arrays.stream(FoodCategoryKeyword.values()).map(Enum::name).toList(),
			Arrays.stream(FoodExcludeKeyword.values()).map(Enum::name).toList());

	private final List<String> allowedKeywords;
	private final List<String> excludedKeywords;

	KakaoCategoryCode(List<String> allowedKeywords, List<String> excludedKeywords) {
		this.allowedKeywords = allowedKeywords;
		this.excludedKeywords = excludedKeywords;
	}

	public boolean matches(String categoryName) {
		if (excludedKeywords.stream().anyMatch(categoryName::contains)) {
			return false;
		}
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

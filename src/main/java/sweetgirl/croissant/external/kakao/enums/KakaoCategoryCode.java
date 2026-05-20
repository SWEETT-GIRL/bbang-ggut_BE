package sweetgirl.croissant.external.kakao.enums;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public enum KakaoCategoryCode {
	CE7(List.of(), Arrays.stream(CafeExcludeKeyword.values()).map(CafeExcludeKeyword::keyword).toList(), List.of()),
	FD6(Arrays.stream(FoodCategoryKeyword.values()).map(Enum::name).toList(),
			Arrays.stream(FoodExcludeKeyword.values()).map(Enum::name).toList(),
			Arrays.stream(FoodPriorityKeyword.values()).map(Enum::name).toList());

	private final List<String> allowedKeywords;
	private final List<String> excludedKeywords;
	private final List<String> priorityIncludeKeywords;

	KakaoCategoryCode(List<String> allowedKeywords, List<String> excludedKeywords,
			List<String> priorityIncludeKeywords) {
		this.allowedKeywords = allowedKeywords;
		this.excludedKeywords = excludedKeywords;
		this.priorityIncludeKeywords = priorityIncludeKeywords;
	}

	public boolean matches(String categoryName) {
		if (priorityIncludeKeywords.stream().anyMatch(categoryName::contains)) {
			return true;
		}
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

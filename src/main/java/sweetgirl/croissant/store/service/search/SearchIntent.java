package sweetgirl.croissant.store.service.search;

import java.util.List;

public record SearchIntent(
		String rawQuery,
		List<String> rawTokens,
		List<String> regionTokens,
		List<String> breadTokens,
		List<String> remainingTokens) {
}

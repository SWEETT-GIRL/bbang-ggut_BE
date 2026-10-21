package sweetgirl.croissant.store.service.search;

import java.util.List;

public record SearchIntent(
		String rawQuery,
		String cleanQuery,
		List<String> rawTokens,
		List<String> regionTokens,
		List<String> breadTokens,
		List<String> remainingTokens) {
}

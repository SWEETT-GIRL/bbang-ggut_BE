package sweetgirl.croissant.store.service.search;

import java.util.Arrays;

public enum SearchSort {
	DISTANCE,
	POPULARITY,
	RELEVANCE;

	public static SearchSort from(String value) {
		if (value == null || value.isBlank()) {
			return RELEVANCE;
		}
		return Arrays.stream(values())
			.filter(sort -> sort.name().equalsIgnoreCase(value))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException(
				"sort must be one of distance, popularity, relevance"));
	}
}

package sweetgirl.croissant.store.service.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.store.repository.SearchRegionAliasRepository;

@Component
@RequiredArgsConstructor
public class StoreSearchQueryParser {

	private static final Set<String> REGION_SUFFIXES = Set.of(
		"시", "군", "구", "동", "읍", "면", "리", "로", "길");

	private final SearchRegionAliasRepository searchRegionAliasRepository;

	public SearchIntent parse(String rawQuery) {
		String normalized = rawQuery == null ? "" : rawQuery.trim().replaceAll("\\s+", " ");
		if (normalized.isBlank()) {
			return new SearchIntent("", List.of(), List.of(), List.of(), List.of());
		}

		List<String> rawTokens = List.of(normalized.split(" "));
		List<String> regionTokens = new ArrayList<>();
		List<String> breadTokens = new ArrayList<>();
		List<String> remainingTokens = new ArrayList<>();
		for (String token : rawTokens) {
			if (isRegionToken(token)) {
				regionTokens.add(token);
			} else {
				breadTokens.add(token);
				remainingTokens.add(token);
			}
		}

		return new SearchIntent(
			normalized,
			rawTokens,
			List.copyOf(regionTokens),
			List.copyOf(breadTokens),
			List.copyOf(remainingTokens));
	}

	private boolean isRegionToken(String token) {
		if (token.length() < 2) {
			return false;
		}
		if (searchRegionAliasRepository.findByNormalizedAlias(normalize(token)).isPresent()) {
			return true;
		}
		return REGION_SUFFIXES.stream().anyMatch(token::endsWith);
	}

	private String normalize(String token) {
		return token.toLowerCase().replaceAll("[^0-9a-zA-Z가-힣]", "");
	}
}

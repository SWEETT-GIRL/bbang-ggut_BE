package sweetgirl.croissant.store.service.search;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.store.repository.SearchRegionAliasRepository;

@Component
@RequiredArgsConstructor
public class StoreSearchQueryParser {

	private static final String BREAD_TYPES_FILE = "bread-types.txt";
	private static final String BREAD_SYNONYMS_FILE = "bread-synonyms.txt";
	private static final Set<String> REGION_SUFFIXES = Set.of(
		"시", "군", "구", "동", "읍", "면", "리", "로", "길");

	private final SearchRegionAliasRepository searchRegionAliasRepository;

	private Set<String> breadTypes;
	private Map<String, String> breadSynonyms;

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
				String canonical = toBreadCanonical(token);
				if (canonical != null) {
					breadTokens.add(canonical);
				} else {
					remainingTokens.add(token);
				}
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

	// 동의어면 대표어로, 대표어면 그대로, 빵 단어 아니면 null
	private String toBreadCanonical(String token) {
		String canonical = getBreadSynonyms().get(token);
		if (canonical != null) {
			return canonical;
		}
		return getBreadTypes().contains(token) ? token : null;
	}

	private Set<String> getBreadTypes() {
		if (breadTypes != null) {
			return breadTypes;
		}
		breadTypes = new java.util.HashSet<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
			new ClassPathResource(BREAD_TYPES_FILE).getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				line = line.strip();
				if (!line.isBlank() && !line.startsWith("#")) {
					breadTypes.add(line);
				}
			}
		} catch (IOException e) {
			// 파일 로드 실패 시 빈 셋으로 동작
		}
		return breadTypes;
	}

	private Map<String, String> getBreadSynonyms() {
		if (breadSynonyms != null) {
			return breadSynonyms;
		}
		breadSynonyms = new HashMap<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
			new ClassPathResource(BREAD_SYNONYMS_FILE).getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				line = line.strip();
				if (line.isBlank() || line.startsWith("#")) {
					continue;
				}
				String[] parts = line.split("=", 2);
				if (parts.length == 2) {
					breadSynonyms.put(parts[0].strip(), parts[1].strip());
				}
			}
		} catch (IOException e) {
			// 파일 로드 실패 시 빈 맵으로 동작
		}
		return breadSynonyms;
	}

	private String normalize(String token) {
		return token.toLowerCase().replaceAll("[^0-9a-zA-Z가-힣]", "");
	}
}

package sweetgirl.croissant.store.service.search;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.store.entity.SearchRegionAlias;
import sweetgirl.croissant.store.repository.SearchRegionAliasRepository;

@Component
@RequiredArgsConstructor
public class StoreSearchQueryParser {

	private static final String BREAD_TYPES_FILE = "bread-types.txt";
	private static final String BREAD_SYNONYMS_FILE = "bread-synonyms.txt";
	private static final int MIN_CONTAINED_BREAD_TOKEN_LENGTH = 2;
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
		Set<String> breadTokens = new LinkedHashSet<>();
		List<String> remainingTokens = new ArrayList<>();
		for (String token : rawTokens) {
			String exactBreadCanonical = toExactBreadCanonical(token);
			if (isRegionToken(token)) {
				regionTokens.add(token);
			} else if (exactBreadCanonical != null) {
				breadTokens.add(exactBreadCanonical);
			} else {
				ParsedToken parsedToken = parseContainedTokens(token);
				regionTokens.addAll(parsedToken.regionTokens());
				breadTokens.addAll(parsedToken.breadTokens());
				remainingTokens.addAll(parsedToken.remainingTokens());
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
	private String toExactBreadCanonical(String token) {
		if (getBreadTypes().contains(token)) {
			return token;
		}
		String canonical = getBreadSynonyms().get(token);
		if (canonical != null) {
			return canonical;
		}
		return null;
	}

	private ParsedToken parseContainedTokens(String token) {
		String normalizedToken = normalize(token);
		List<MatchedTerm> matches = containedRegionCandidates(normalizedToken).stream()
			.map(candidate -> new MatchedTerm(candidate.term(), candidate.value(), TermType.REGION,
				normalizedToken.indexOf(candidate.term())))
			.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
		candidateBreadTerms().stream()
			.filter(candidate -> candidate.term().length() >= MIN_CONTAINED_BREAD_TOKEN_LENGTH)
			.filter(candidate -> normalizedToken.contains(candidate.term()))
			.map(candidate -> new MatchedTerm(candidate.term(), candidate.canonical(), TermType.BREAD,
				normalizedToken.indexOf(candidate.term())))
			.forEach(matches::add);

		List<MatchedTerm> selected = new ArrayList<>();
		matches.stream()
			.sorted(Comparator
				.<MatchedTerm>comparingInt(match -> match.term().length())
				.reversed()
				.thenComparingInt(MatchedTerm::start))
			.forEach(match -> {
				boolean overlapsSelectedTerm = selected.stream().anyMatch(match::overlaps);
				if (!overlapsSelectedTerm) {
					selected.add(match);
				}
			});

		if (selected.isEmpty()) {
			return new ParsedToken(List.of(), List.of(), List.of(token));
		}

		selected.sort(Comparator.comparingInt(MatchedTerm::start));
		List<String> regionTokens = new ArrayList<>();
		Set<String> breadTokens = new LinkedHashSet<>();
		List<String> remainingTokens = new ArrayList<>();
		int cursor = 0;
		for (MatchedTerm match : selected) {
			addRemaining(normalizedToken.substring(cursor, match.start()), remainingTokens);
			if (match.type() == TermType.REGION) {
				regionTokens.add(match.value());
			} else {
				breadTokens.add(match.value());
			}
			cursor = match.end();
		}
		addRemaining(normalizedToken.substring(cursor), remainingTokens);
		return new ParsedToken(regionTokens, List.copyOf(breadTokens), remainingTokens);
	}

	private void addRemaining(String value, List<String> remainingTokens) {
		if (!value.isBlank()) {
			remainingTokens.add(value);
		}
	}

	private List<RegionTermCandidate> containedRegionCandidates(String normalizedToken) {
		return searchRegionAliasRepository.findAll().stream()
			.map(SearchRegionAlias::getNormalizedAlias)
			.filter(alias -> alias != null && alias.length() >= 2)
			.filter(normalizedToken::contains)
			.map(alias -> new RegionTermCandidate(alias, alias))
			.toList();
	}

	private List<BreadTermCandidate> candidateBreadTerms() {
		List<BreadTermCandidate> candidates = new ArrayList<>();
		getBreadTypes().forEach(type -> candidates.add(new BreadTermCandidate(type, type)));
		getBreadSynonyms().forEach((synonym, canonical) -> {
			if (!getBreadTypes().contains(synonym)) {
				candidates.add(new BreadTermCandidate(synonym, canonical));
			}
		});
		return candidates;
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

	private enum TermType {
		REGION,
		BREAD
	}

	private record ParsedToken(
			List<String> regionTokens,
			List<String> breadTokens,
			List<String> remainingTokens) {
	}

	private record MatchedTerm(String term, String value, TermType type, int start) {
		private int end() {
			return start + term.length();
		}

		private boolean overlaps(MatchedTerm other) {
			return start < other.end() && other.start() < end();
		}
	}

	private record RegionTermCandidate(String term, String value) {
	}

	private record BreadTermCandidate(String term, String canonical) {
	}
}

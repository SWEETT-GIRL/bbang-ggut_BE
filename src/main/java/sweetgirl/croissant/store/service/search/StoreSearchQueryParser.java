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

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.store.entity.SearchRegionAlias;
import sweetgirl.croissant.store.repository.SearchRegionAliasRepository;

@Slf4j
@Component
@RequiredArgsConstructor
public class StoreSearchQueryParser {

	private static final String BREAD_TYPES_FILE = "bread-types.txt";
	private static final String BREAD_SYNONYMS_FILE = "bread-synonyms.txt";
	private static final String STOP_WORDS_FILE = "stop-words.txt";
	private static final int MIN_CONTAINED_BREAD_TOKEN_LENGTH = 2;

	private final SearchRegionAliasRepository searchRegionAliasRepository;

	private Set<String> breadTypes;
	private Map<String, String> breadSynonyms;
	private Set<String> stopWords;
	private volatile Set<String> regionAliases;

	// 기본: 가게명 우선 — 붙어쓴 토큰은 지역어든 빵 단어든 분해하지 않고 통째로 검색한다.
	public SearchIntent parse(String rawQuery) {
		return parse(rawQuery, false);
	}

	// 폴백: 1차(가게명/메뉴 직접 매칭)가 0건일 때만 붙어쓴 토큰을 분해한다.
	// "초코소금빵"→빵 "소금빵", "한남에그타르트"→지역 "한남" + 빵 "에그타르트".
	public SearchIntent parseWithGluedTokenFallback(String rawQuery) {
		return parse(rawQuery, true);
	}

	private SearchIntent parse(String rawQuery, boolean decomposeGluedTokens) {
		String normalized = rawQuery == null ? "" : rawQuery.trim().replaceAll("\\s+", " ");
		if (normalized.isBlank()) {
			return new SearchIntent("", "", List.of(), List.of(), List.of(), List.of());
		}

		Set<String> stopWordSet = getStopWords();
		List<String> rawTokens = List.of(normalized.split(" ")); //공백 단위로 쪼개기
		List<String> regionTokens = new ArrayList<>();
		Set<String> breadTokens = new LinkedHashSet<>();
		List<String> remainingTokens = new ArrayList<>();

		for (String token : rawTokens) {
			String exactBreadCanonical = toExactBreadCanonical(token);
			if (isRegionToken(token)) { //지역토큰 먼저
				regionTokens.add(token);
			} else if (exactBreadCanonical != null) { //빵토큰 두번째
				breadTokens.add(exactBreadCanonical);
			} else { //띄어쓰기 무시하고 다 붙어진 단어에서 찾기
				ParsedToken parsedToken = parseContainedTokens(token, decomposeGluedTokens);
				regionTokens.addAll(parsedToken.regionTokens());
				breadTokens.addAll(parsedToken.breadTokens());
				remainingTokens.addAll(parsedToken.remainingTokens());
			}
		}

		List<String> filteredTokens = rawTokens.stream()
			.filter(t -> !stopWordSet.contains(t.toLowerCase()))
			.toList(); //띄어쓰기 기준으로 불용어 거르기
		String cleanQuery = filteredTokens.isEmpty() ? normalized : String.join(" ", filteredTokens);

		return new SearchIntent(
			normalized,
			cleanQuery,
			rawTokens,
			List.copyOf(regionTokens),
			List.copyOf(breadTokens),
			List.copyOf(remainingTokens));
	}

	private boolean isRegionToken(String token) {
		if (token.length() < 2) {
			return false;
		}
		// 지역 판정은 search_region_alias 조회로 일원화한다.
		// 접미사(시·구·동·리…) 기반 추측은 오탐("갤러리"→'리')을 유발하고 진실원천이 이원화되므로 폐기.
		// 실제 등록된 지역 표기(store.region_3rd 흡수 포함)만 지역으로 인정한다.
		return getRegionAliases().contains(normalize(token));
	}

	// 부팅 완료 후 지역 alias(normalized) 전체를 메모리에 1회 적재한다.
	// alias는 Flyway 마이그레이션으로만 변경되는 닫힌 집합(런타임 write 없음)이라 무효화가 필요 없고,
	// 검색마다 findAll()로 테이블을 로드하던 비용(요청 폭주 시 GC 압력)을 제거한다.
	@EventListener(ApplicationReadyEvent.class)
	public void loadRegionAliases() {
		reloadRegionAliasesIfAbsent();
	}

	// 실제 적재 로직. 여러 스레드가 동시에 findAll을 호출하지 않도록 동기화(이중 검사)한다.
	// 프리로드가 DB 문제로 실패해도 부팅을 막지 않는다: 예외를 로깅만 하고 캐시를 null로 남겨
	// getRegionAliases()의 지연 로드로 이후 재시도되게 한다.
	private synchronized void reloadRegionAliasesIfAbsent() {
		if (regionAliases != null) {
			return;
		}
		try {
			this.regionAliases = searchRegionAliasRepository.findAll().stream()
				.map(SearchRegionAlias::getNormalizedAlias)
				.filter(alias -> alias != null && !alias.isBlank())
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		} catch (RuntimeException e) {
			log.warn("지역 alias 프리로드 실패 — 최초 검색 시 지연 로드로 재시도합니다.", e);
		}
	}

	// 부팅 이벤트 이전 접근(단위 테스트 등)·프리로드 실패에 대한 지연 로드 안전망.
	// 적재 전까지는 빈 집합을 반환해 NPE 없이 지역 판정만 건너뛴다(다음 호출에서 재시도).
	private Set<String> getRegionAliases() {
		Set<String> cached = regionAliases;
		if (cached != null) {
			return cached;
		}
		reloadRegionAliasesIfAbsent();
		Set<String> loaded = regionAliases;
		return loaded != null ? loaded : Set.of();
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

	private ParsedToken parseContainedTokens(String token, boolean decomposeGluedTokens) {
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

		if (!decomposeGluedTokens) {
			// 가게명 우선(1차) 모드: 붙어쓴 토큰은 지역어가 박혀 있어도 분해하지 않고 원문 그대로
			// 가게명/메뉴 검색(rawQuery)에 넘긴다.
			//
			// 분해하면 그 지역·빵의 가게가 통째로 결과셋에 들어와 정작 찾던 가게가 묻힌다.
			// "소금도화"(가게명)에서 "도화"를 지역으로 분해해 도화동 가게들에 밀린 사례가 있었고,
			// "브릭베이글"에서 "베이글"을 분해했을 때와 같은 증상이다.
			//
			// 1차가 0건일 때만 폴백에서 분해한다("한남에그타르트" → 한남 + 에그타르트).
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
		return getRegionAliases().stream()
			.filter(alias -> alias.length() >= 2)
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

	private Set<String> getStopWords() {
		if (stopWords != null) {
			return stopWords;
		}
		stopWords = new java.util.HashSet<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
			new ClassPathResource(STOP_WORDS_FILE).getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				line = line.strip();
				if (line.isBlank() || line.startsWith("#") || line.contains(" ")) {
					continue;
				}
				stopWords.add(line.toLowerCase());
			}
		} catch (IOException e) {
			// 파일 로드 실패 시 빈 셋으로 동작
		}
		return stopWords;
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

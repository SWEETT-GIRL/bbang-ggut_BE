package sweetgirl.croissant.store.service.search;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.store.entity.SearchKeywordCorrection;
import sweetgirl.croissant.store.repository.SearchKeywordCorrectionRepository;

/**
 * 검색어 교정을 검색·자동완성 앞단에서 적용한다 — "서심당" → "성심당", "콜버 탄방" → "콜드버터베이크샵 탄방".
 *
 * <p>검색어 전체가 교정 목록에 있으면 통째로 바꾸고, 없으면 공백으로 나눈 단어마다 바꾼다. 한 번만 적용한다 — 바뀐 결과를 다시
 * 교정하지 않아 A→B, B→A 같은 순환이 생겨도 멈춘다.
 *
 * <p>검색은 사용자 요청 경로라 매번 DB 를 읽지 않는다. 메모리에 들고 있다가 {@link #REFRESH_INTERVAL}이 지나면 다음 접근에서 다시
 * 읽는다. 같은 인스턴스에서 교정을 넣으면 {@link #invalidate()}로 바로 반영하고, blue-green 의 다른 컨테이너는 길어야 그만큼
 * 늦는다. 다시 읽다 실패하면 직전 목록을 그대로 쓴다 — 교정 때문에 검색이 실패하면 안 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchKeywordCorrector {

	static final Duration REFRESH_INTERVAL = Duration.ofMinutes(5);
	private static final int MIN_TOKEN_LENGTH = 2;

	private final SearchKeywordCorrectionRepository searchKeywordCorrectionRepository;

	private volatile Map<String, String> corrections = Map.of();
	private volatile long loadedAtNanos;
	private volatile boolean loaded;

	/** 검색용 — 검색어 전체, 없으면 단어 단위로 바꾼다. 바꿀 것이 없으면 원문 그대로. */
	public String correct(String keyword) {
		if (keyword == null || keyword.isBlank()) {
			return keyword;
		}
		Map<String, String> current = current();
		if (current.isEmpty()) {
			return keyword;
		}
		String whole = current.get(SearchTextNormalizer.normalize(keyword));
		if (whole != null) {
			return whole;
		}
		List<String> tokens = new ArrayList<>();
		boolean changed = false;
		for (String token : keyword.trim().split("\\s+")) {
			String normalized = SearchTextNormalizer.normalize(token);
			String replacement = normalized.length() >= MIN_TOKEN_LENGTH ? current.get(normalized) : null;
			tokens.add(replacement == null ? token : replacement);
			changed |= replacement != null;
		}
		return changed ? String.join(" ", tokens) : keyword;
	}

	/**
	 * 자동완성용 — 검색어 전체가 일치할 때만 바꾼다. 자동완성은 치는 도중의 글자라 단어 단위로 바꾸면 쓰다 만 말이 엉뚱하게 바뀐다.
	 */
	public String correctWhole(String keyword) {
		if (keyword == null || keyword.isBlank()) {
			return keyword;
		}
		return current().getOrDefault(SearchTextNormalizer.normalize(keyword), keyword);
	}

	/** 교정을 넣거나 지운 직후 부른다. 다음 접근에서 다시 읽는다. */
	public void invalidate() {
		loaded = false;
	}

	private Map<String, String> current() {
		if (loaded && System.nanoTime() - loadedAtNanos < REFRESH_INTERVAL.toNanos()) {
			return corrections;
		}
		synchronized (this) {
			if (!loaded || System.nanoTime() - loadedAtNanos >= REFRESH_INTERVAL.toNanos()) {
				reload();
			}
			return corrections;
		}
	}

	private void reload() {
		try {
			corrections = searchKeywordCorrectionRepository.findAll().stream()
				.collect(Collectors.toUnmodifiableMap(SearchKeywordCorrection::getNormalizedKeyword,
					SearchKeywordCorrection::getCorrectedQuery, (first, second) -> first));
		} catch (RuntimeException exception) {
			log.warn("[검색어 교정] 목록을 다시 읽지 못해 직전 목록을 쓴다 size={}", corrections.size(), exception);
		}
		// 실패해도 시각을 찍는다 — DB 장애 때 검색마다 재시도해 장애를 키우지 않게
		loadedAtNanos = System.nanoTime();
		loaded = true;
	}
}

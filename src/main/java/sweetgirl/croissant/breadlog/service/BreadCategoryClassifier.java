package sweetgirl.croissant.breadlog.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import sweetgirl.croissant.menu.enums.MenuKind;

/**
 * 빵로그 태그 이름 → 분류(MenuKind 이름). 메뉴 쪽 dataops 분류기와 같은 "마지막 제품 타입 단어" 원리.
 *
 * <p>왜 contains 가 아니라 끝단어인가 — "티라미수 크로와상" 은 크루아상이지 티라미수가 아니다.
 * 이전 contains 방식은 사전에 먼저 걸리는 쪽이 이겨서 맛 단어에 끌려갔고, 동의어(시오빵)도 안 봤다.
 * 표는 {@code bread-record-categories.txt} — 변형어를 코드 수정 없이 보강한다.
 */
@Component
public class BreadCategoryClassifier {

	private static final String END_WORDS_FILE = "bread-record-categories.txt";
	private static final String FALLBACK_CATEGORY = "기타";
	private static final Pattern PARENTHESIZED = Pattern.compile("\\([^()]*\\)");
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	/** kind 이름 → 끝단어 변형들. 파일 순서 유지 */
	private final Map<String, List<String>> endWordsByKind;

	public BreadCategoryClassifier() {
		this.endWordsByKind = loadEndWords();
	}

	public String classify(String breadName) {
		if (breadName == null) {
			return FALLBACK_CATEGORY;
		}
		String name = normalize(breadName);
		if (name.isEmpty()) {
			return FALLBACK_CATEGORY;
		}
		String best = null;
		int bestEnd = -1;
		int bestLength = -1;
		for (Map.Entry<String, List<String>> entry : endWordsByKind.entrySet()) {
			for (String word : entry.getValue()) {
				int index = name.lastIndexOf(word);
				if (index < 0) {
					continue;
				}
				int end = index + word.length();
				// 더 뒤에서 끝나면 교체, 동률이면 더 긴(구체적인) 단어 — 에그타르트 > 타르트, 버터떡 > 떡
				if (end > bestEnd || (end == bestEnd && word.length() > bestLength)) {
					best = entry.getKey();
					bestEnd = end;
					bestLength = word.length();
				}
			}
		}
		return best == null ? FALLBACK_CATEGORY : best;
	}

	/** 괄호 수식·공백 제거, 소문자 — "미니꽃설기(최소주문 10개)" → "미니꽃설기" */
	private String normalize(String value) {
		String withoutParentheses = PARENTHESIZED.matcher(value).replaceAll(" ");
		return WHITESPACE.matcher(withoutParentheses).replaceAll("").toLowerCase(Locale.ROOT);
	}

	private static Map<String, List<String>> loadEndWords() {
		Map<String, List<String>> mapping = new LinkedHashMap<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
			new ClassPathResource(END_WORDS_FILE).getInputStream(), StandardCharsets.UTF_8))) {
			reader.lines()
				.map(String::strip)
				.filter(line -> !line.isBlank() && !line.startsWith("#"))
				.forEach(line -> mapping.put(kindOf(line), wordsOf(line)));
		} catch (IOException e) {
			throw new IllegalStateException("빵로그 분류표를 읽을 수 없습니다: " + END_WORDS_FILE, e);
		}
		return mapping;
	}

	/** 표의 kind 는 MenuKind 이름이어야 한다 — 오타가 있으면 기동 시 바로 드러나게 */
	private static String kindOf(String line) {
		String kind = line.substring(0, line.indexOf('=')).strip();
		try {
			MenuKind.valueOf(kind);
		} catch (IllegalArgumentException notAKind) {
			throw new IllegalStateException(END_WORDS_FILE + " 의 kind 가 MenuKind 에 없습니다: " + kind, notAKind);
		}
		return kind;
	}

	private static List<String> wordsOf(String line) {
		List<String> words = new ArrayList<>();
		Arrays.stream(line.substring(line.indexOf('=') + 1).split(","))
			.map(word -> word.strip().toLowerCase(Locale.ROOT))
			.filter(word -> !word.isEmpty())
			.forEach(words::add);
		return words;
	}
}

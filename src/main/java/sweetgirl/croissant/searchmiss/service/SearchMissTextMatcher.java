package sweetgirl.croissant.searchmiss.service;

import java.util.regex.Pattern;

import sweetgirl.croissant.store.service.search.SearchTextNormalizer;

/**
 * 실패 검색어 판정에 쓰는 문자열 규칙. 전부 결정적이라 단위 테스트로 고정한다.
 *
 * <p>정규화는 검색과 같은 규칙({@link SearchTextNormalizer})을 쓴다 — 다르면 "판정은 같다는데 검색은 못 찾는" 어긋남이 생긴다.
 */
public final class SearchMissTextMatcher {

	private static final Pattern JAMO_ONLY = Pattern.compile("^[ㄱ-ㅎㅏ-ㅣ\\s]+$");
	private static final Pattern DIGITS_ONLY = Pattern.compile("^[0-9\\s]+$");
	// "런던베이글뮤지엄 안국점", "런던베이글뮤지엄(안국점)" — 띄어 쓰거나 괄호로 붙인 마지막 "○○점"만 뗀다.
	// 붙여 쓴 "○○점"은 가게명 일부일 수 있어("빵점") 건드리지 않는다.
	private static final Pattern BRANCH_SUFFIX = Pattern.compile("[\\s(]+[^\\s()]*점\\)?\\s*$");
	private static final int MIN_NAME_LENGTH = 2;
	private static final double NAME_SIMILARITY_THRESHOLD = 0.7;
	private static final int SHORT_KEYWORD_LENGTH = 4;

	private SearchMissTextMatcher() {
		// 정적 유틸리티
	}

	/** 자모만·숫자만·정규화 후 한 글자·같은 글자 반복. 가게를 찾은 입력이 아니다. */
	public static boolean isJunk(String keyword) {
		if (keyword == null || keyword.isBlank()) {
			return true;
		}
		String trimmed = keyword.trim();
		if (JAMO_ONLY.matcher(trimmed).matches() || DIGITS_ONLY.matcher(trimmed).matches()) {
			return true;
		}
		String normalized = SearchTextNormalizer.normalize(trimmed);
		if (normalized.length() < MIN_NAME_LENGTH) {
			return true;
		}
		return normalized.length() >= 3 && normalized.chars().distinct().count() == 1;
	}

	/** 지점명을 떼고 정규화한 이름. 지점까지 떼면 빈 문자열이 되는 이름("안국점")은 그대로 둔다. */
	public static String baseName(String storeName) {
		if (storeName == null) {
			return "";
		}
		String stripped = BRANCH_SUFFIX.matcher(storeName).replaceFirst("");
		String normalized = SearchTextNormalizer.normalize(stripped);
		return normalized.isEmpty() ? SearchTextNormalizer.normalize(storeName) : normalized;
	}

	/**
	 * 검색어가 이 가게 이름을 가리키는 말인가 — 포함, 줄임말, 오타 중 하나.
	 *
	 * <p>네이버 지역검색은 검색어와 느슨하게만 관련된 장소도 돌려준다. 이 검사를 안 거치면 엉뚱한 가게에 별칭이 붙는다.
	 */
	public static boolean isRelated(String keyword, String storeName) {
		String normalizedKeyword = SearchTextNormalizer.normalize(keyword);
		String fullName = SearchTextNormalizer.normalize(storeName);
		String base = baseName(storeName);
		if (normalizedKeyword.length() < MIN_NAME_LENGTH || base.length() < MIN_NAME_LENGTH) {
			return false;
		}
		return fullName.contains(normalizedKeyword)
			|| normalizedKeyword.contains(base)
			|| isAbbreviation(normalizedKeyword, base)
			|| isTypo(normalizedKeyword, base);
	}

	/**
	 * 줄임말인가 — 첫 글자가 같고, 검색어의 글자가 이름에 순서대로 들어 있다(런베뮤 ⊂ 런던베이글뮤지엄).
	 *
	 * <p>첫 글자를 맞추게 한 것은 한국어 줄임말이 대부분 첫 음절에서 시작하기 때문이다. 이 조건이 없으면 짧은 검색어가
	 * 긴 이름 아무 데나 흩어져 맞는다.
	 */
	static boolean isAbbreviation(String normalizedKeyword, String normalizedName) {
		if (normalizedKeyword.length() >= normalizedName.length()
			|| normalizedKeyword.charAt(0) != normalizedName.charAt(0)) {
			return false;
		}
		int position = 0;
		for (char character : normalizedKeyword.toCharArray()) {
			position = normalizedName.indexOf(character, position);
			if (position < 0) {
				return false;
			}
			position++;
		}
		return true;
	}

	/** 오타인가 — 짧은 말은 한 글자, 긴 말은 두 글자까지 다른 것을 허용한다. */
	static boolean isTypo(String normalizedKeyword, String normalizedName) {
		if (normalizedKeyword.length() < 3 || normalizedName.length() < 3) {
			return false;
		}
		int allowed = Math.min(normalizedKeyword.length(), normalizedName.length()) <= SHORT_KEYWORD_LENGTH ? 1 : 2;
		return levenshtein(normalizedKeyword, normalizedName) <= allowed;
	}

	/** 네이버 장소 이름과 우리 가게 이름이 같은 가게를 가리키나 — 지점명을 뗀 뒤 같거나, 포함하거나, 충분히 비슷하다. */
	public static boolean isSameStoreName(String left, String right) {
		String leftBase = baseName(left);
		String rightBase = baseName(right);
		if (leftBase.length() < MIN_NAME_LENGTH || rightBase.length() < MIN_NAME_LENGTH) {
			return false;
		}
		if (leftBase.equals(rightBase) || leftBase.contains(rightBase) || rightBase.contains(leftBase)) {
			return true;
		}
		return similarity(leftBase, rightBase) >= NAME_SIMILARITY_THRESHOLD;
	}

	/** 이름이 글에 들어 있나. 띄어쓰기가 달라도("런던 베이글 뮤지엄") 맞게 둘 다 정규화해 본다. */
	public static boolean appearsIn(String name, String text) {
		String normalizedName = SearchTextNormalizer.normalize(name);
		return normalizedName.length() >= MIN_NAME_LENGTH
			&& SearchTextNormalizer.normalize(text).contains(normalizedName);
	}

	private static double similarity(String left, String right) {
		int longer = Math.max(left.length(), right.length());
		return 1.0 - (double)levenshtein(left, right) / longer;
	}

	private static int levenshtein(String left, String right) {
		int[] previous = new int[right.length() + 1];
		int[] current = new int[right.length() + 1];
		for (int column = 0; column <= right.length(); column++) {
			previous[column] = column;
		}
		for (int row = 1; row <= left.length(); row++) {
			current[0] = row;
			for (int column = 1; column <= right.length(); column++) {
				int cost = left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1;
				current[column] = Math.min(Math.min(current[column - 1] + 1, previous[column] + 1),
					previous[column - 1] + cost);
			}
			int[] swap = previous;
			previous = current;
			current = swap;
		}
		return previous[right.length()];
	}
}

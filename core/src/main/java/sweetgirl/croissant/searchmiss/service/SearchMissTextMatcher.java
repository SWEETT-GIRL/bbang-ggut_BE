package sweetgirl.croissant.searchmiss.service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sweetgirl.croissant.store.service.search.SearchTextNormalizer;

/**
 * 실패 검색어 판정에 쓰는 문자열 규칙. 전부 결정적이라 단위 테스트로 고정한다.
 *
 * <p>정규화는 검색과 같은 규칙({@link SearchTextNormalizer})을 쓴다 — 다르면 "판정은 같다는데 검색은 못 찾는" 어긋남이 생긴다.
 *
 * <p>오타는 <b>자모 단위</b>로 잰다. 글자 단위로 재면 "퉁퉁이 ↔ 통통이"(모음만 둘 바뀜)와 "케익부티크 ↔ 잍아부티크"(앞 두 글자가 통째로
 * 다름)가 똑같이 두 글자 차이라 가를 수 없다(운영 1회차 10/17 오매칭).
 */
public final class SearchMissTextMatcher {

	private static final Pattern JAMO_ONLY = Pattern.compile("^[ㄱ-ㅎㅏ-ㅣ\\s]+$");
	private static final Pattern DIGITS_ONLY = Pattern.compile("^[0-9\\s]+$");
	private static final Pattern HANGUL = Pattern.compile("[가-힣ㄱ-ㅎㅏ-ㅣ]");
	private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
	private static final Pattern PHONE = Pattern.compile("0\\d{1,2}[-\\s.]?\\d{3,4}[-\\s.]?\\d{4}");
	// "런던베이글뮤지엄 안국점", "런던베이글뮤지엄(안국점)" — 띄어 쓰거나 괄호로 붙인 마지막 "○○점"만 뗀다.
	// 붙여 쓴 "○○점"은 가게명 일부일 수 있어("빵점") 건드리지 않는다.
	private static final Pattern BRANCH_SUFFIX = Pattern.compile("[\\s(]+[^\\s()]*점\\)?\\s*$");
	private static final int MIN_NAME_LENGTH = 2;
	// 두 글자는 자모 하나만 달라도 전혀 다른 말이 되기 쉽다("콜버" ↔ "콜바") — 오타는 세 글자부터 본다
	private static final int MIN_TYPO_LENGTH = 3;
	private static final double NAME_SIMILARITY_THRESHOLD = 0.7;
	// 자모 6개(대략 두세 글자) 이하는 1개, 그보다 길면 2개까지 다른 것을 오타로 본다
	private static final int SHORT_JAMO_LENGTH = 6;

	private static final char[] CHOSEONG = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ".toCharArray();
	private static final char[] JUNGSEONG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ".toCharArray();
	private static final char[] JONGSEONG = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ".toCharArray();
	private static final char HANGUL_BASE = '가';
	private static final char HANGUL_LAST = '힣';
	private static final int JUNGSEONG_COUNT = 21;
	private static final int JONGSEONG_COUNT = 28;

	private SearchMissTextMatcher() {
		// 정적 유틸리티
	}

	/** 자모만·숫자만·정규화 후 한 글자·같은 글자 반복·개인정보. 가게를 찾은 입력이 아니다. */
	public static boolean isJunk(String keyword) {
		if (keyword == null || keyword.isBlank()) {
			return true;
		}
		String trimmed = keyword.trim();
		if (JAMO_ONLY.matcher(trimmed).matches() || DIGITS_ONLY.matcher(trimmed).matches()
			|| containsPersonalInfo(trimmed)) {
			return true;
		}
		String normalized = SearchTextNormalizer.normalize(trimmed);
		if (normalized.length() < MIN_NAME_LENGTH) {
			return true;
		}
		return normalized.length() >= 3 && normalized.chars().distinct().count() == 1;
	}

	/** 이메일·전화번호가 들어 있나. 검색창에 잘못 붙여 넣은 개인정보다(운영 1회차에 이메일 1건). */
	public static boolean containsPersonalInfo(String keyword) {
		return keyword != null && (EMAIL.matcher(keyword).find() || PHONE.matcher(keyword).find());
	}

	/** 리포트에 싣기 전에 이메일·전화번호를 가린다. "ab***@gmail.com", "010-****-5678" */
	public static String maskPersonalInfo(String keyword) {
		if (keyword == null) {
			return "";
		}
		Matcher email = EMAIL.matcher(keyword);
		StringBuilder masked = new StringBuilder();
		while (email.find()) {
			String value = email.group();
			int at = value.indexOf('@');
			String local = value.substring(0, at);
			email.appendReplacement(masked,
				Matcher
					.quoteReplacement(local.substring(0, Math.min(2, local.length())) + "***" + value.substring(at)));
		}
		email.appendTail(masked);
		return PHONE.matcher(masked.toString()).replaceAll(match -> {
			String digits = match.group().replaceAll("\\D", "");
			return digits.substring(0, 3) + "-****-" + digits.substring(digits.length() - 4);
		});
	}

	public static boolean hasHangul(String keyword) {
		return keyword != null && HANGUL.matcher(keyword).find();
	}

	/** 지점명을 떼고 정규화한 이름. 지점까지 떼면 빈 문자열이 되는 이름("안국점")은 그대로 둔다. */
	public static String baseName(String storeName) {
		if (storeName == null) {
			return "";
		}
		String normalized = SearchTextNormalizer.normalize(stripBranch(storeName));
		return normalized.isEmpty() ? SearchTextNormalizer.normalize(storeName) : normalized;
	}

	/** 지점명만 뗀 원래 표기. 교정 대상 검색어로 쓴다 — "콜드버터베이크샵 탄방점" → "콜드버터베이크샵" */
	public static String displayBaseName(String storeName) {
		if (storeName == null) {
			return "";
		}
		String stripped = stripBranch(storeName).trim();
		return stripped.isEmpty() ? storeName.trim() : stripped;
	}

	private static String stripBranch(String storeName) {
		return BRANCH_SUFFIX.matcher(storeName).replaceFirst("");
	}

	/**
	 * 검색어가 이 이름을 가리키는 말인가 — 포함, 줄임말, 오타(자모), 앞부분 오타 중 하나.
	 *
	 * <p>네이버 지역검색은 검색어와 느슨하게만 관련된 장소도 돌려준다. 이 검사를 안 거치면 엉뚱한 가게로 교정된다.
	 *
	 * <p>앞부분 비교는 "뚜쥬르 ↔ 뚜쥬루 빵돌가마마을"처럼 브랜드 오타를 지점이 붙은 긴 이름에서 찾기 위해서다. 이름 전체와 견주면
	 * 길이 차이 때문에 오타로 안 잡힌다(운영 1회차 10/17).
	 */
	public static boolean isRelated(String keyword, String name) {
		String normalizedKeyword = SearchTextNormalizer.normalize(keyword);
		String fullName = SearchTextNormalizer.normalize(name);
		String base = baseName(name);
		if (normalizedKeyword.length() < MIN_NAME_LENGTH || base.length() < MIN_NAME_LENGTH) {
			return false;
		}
		return fullName.contains(normalizedKeyword)
			|| normalizedKeyword.contains(base)
			|| isAbbreviation(normalizedKeyword, base)
			|| isTypo(normalizedKeyword, base)
			|| isPrefixTypo(normalizedKeyword, base);
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

	/** 오타인가 — 자모로 쪼개 짧은 말은 1개, 긴 말은 2개까지 다른 것을 허용한다. 같은 말은 오타가 아니다. */
	static boolean isTypo(String normalizedKeyword, String normalizedName) {
		if (normalizedKeyword.length() < MIN_TYPO_LENGTH || normalizedName.length() < MIN_TYPO_LENGTH
			|| normalizedKeyword.equals(normalizedName)) {
			return false;
		}
		String keywordJamo = toJamo(normalizedKeyword);
		String nameJamo = toJamo(normalizedName);
		int allowed = Math.min(keywordJamo.length(), nameJamo.length()) <= SHORT_JAMO_LENGTH ? 1 : 2;
		return levenshtein(keywordJamo, nameJamo) <= allowed;
	}

	/** 이름 앞부분(검색어와 같은 글자 수)과 오타 관계인가. */
	static boolean isPrefixTypo(String normalizedKeyword, String normalizedName) {
		if (normalizedKeyword.length() < MIN_TYPO_LENGTH
			|| normalizedName.length() <= normalizedKeyword.length()) {
			return false;
		}
		return isTypo(normalizedKeyword, normalizedName.substring(0, normalizedKeyword.length()));
	}

	/** 한글 음절을 초성·중성·종성으로 쪼갠다. 한글이 아닌 글자는 그대로 둔다. */
	static String toJamo(String text) {
		StringBuilder jamo = new StringBuilder();
		for (char character : text.toCharArray()) {
			if (character < HANGUL_BASE || character > HANGUL_LAST) {
				jamo.append(character);
				continue;
			}
			int index = character - HANGUL_BASE;
			jamo.append(CHOSEONG[index / (JUNGSEONG_COUNT * JONGSEONG_COUNT)]);
			jamo.append(JUNGSEONG[(index % (JUNGSEONG_COUNT * JONGSEONG_COUNT)) / JONGSEONG_COUNT]);
			int jongseong = index % JONGSEONG_COUNT;
			if (jongseong > 0) {
				jamo.append(JONGSEONG[jongseong]);
			}
		}
		return jamo.toString();
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

	/**
	 * 정규화한 이름들의 공통 앞부분. 두 글자보다 짧으면 빈 문자열이다.
	 *
	 * <p>"뚜쥬루빵돌가마마을, 뚜쥬루과자점, 뚜쥬루어린이베이커리" → "뚜쥬루". 이름이 여럿인 체인에서 브랜드를 뽑는 데 쓴다.
	 */
	public static String commonPrefix(List<String> normalizedNames) {
		if (normalizedNames.isEmpty()) {
			return "";
		}
		String prefix = normalizedNames.get(0);
		for (String name : normalizedNames) {
			int length = 0;
			while (length < prefix.length() && length < name.length() && prefix.charAt(length) == name.charAt(length)) {
				length++;
			}
			prefix = prefix.substring(0, length);
		}
		return prefix.length() >= MIN_NAME_LENGTH ? prefix : "";
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

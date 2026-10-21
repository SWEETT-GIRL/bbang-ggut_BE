package sweetgirl.croissant.common;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 앞말의 받침에 따라 갈리는 한국어 조사 · 어미.
 *
 * <p>템플릿에 구멍을 뚫고 종류 이름을 끼우면 조사가 어긋난다 — {@code "케이크과"} · {@code "소금빵예요"} 같은 것이
 * 그대로 사용자에게 나간다. 예외가 나지 않으므로 테스트가 없으면 알 방법이 없다.
 *
 * <p><b>지금 쓰는 템플릿에 필요한 것만 둔다.</b> {@code 으로/로} 는 이유 문장에 안 쓰여서 넣지 않았다 — 그것은
 * 받침 유무만으로 갈리지 않고 ㄹ 받침이 예외라({@code 베이글로}, {@code 베이글으로} 가 아니다) 규칙이 하나 더 는다.
 * 쓰게 되는 날 그 예외까지 같이 넣는다.
 *
 * <p>한글로 끝나지 않는 말(영문 · 숫자)은 받침을 정할 수 없다. 운영 대표 종류 85개는 전부 한글로 끝나지만,
 * 새 종류가 그렇지 않을 수 있으므로 {@link #endsWithHangul(String)} 으로 가려 <b>조사가 필요 없는 템플릿</b>을 쓴다.
 * 읽는 법을 추측해 붙이지 않는다 — {@code "MOF예요"} 가 맞는지 {@code "MOF이에요"} 가 맞는지는 읽는 사람마다 다르다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Particles {

	private static final char HANGUL_FIRST = 0xAC00;
	private static final char HANGUL_LAST = 0xD7A3;
	/** 한글 음절 = (초성 × 21 + 중성) × 28 + 종성. 28 로 나눈 나머지가 종성 번호이고 0 이면 받침이 없다 */
	private static final int JONGSEONG_COUNT = 28;

	/** 조사를 붙일 수 있는 말인가 — 한글 음절로 끝나야 받침을 정할 수 있다 */
	public static boolean endsWithHangul(String word) {
		if (word == null || word.isEmpty()) {
			return false;
		}
		char last = word.charAt(word.length() - 1);
		return last >= HANGUL_FIRST && last <= HANGUL_LAST;
	}

	/** 받침이 있는가. 한글로 끝나지 않으면 false — 부르는 쪽이 {@link #endsWithHangul} 로 먼저 가른다 */
	public static boolean hasJongseong(String word) {
		return endsWithHangul(word) && (word.charAt(word.length() - 1) - HANGUL_FIRST) % JONGSEONG_COUNT != 0;
	}

	/** 서술격 어미 — 소금빵<b>이에요</b> · 케이크<b>예요</b> */
	public static String copula(String word) {
		return hasJongseong(word) ? "이에요" : "예요";
	}

	/** 주격 조사 — 소금빵<b>이</b> · 케이크<b>가</b> */
	public static String subject(String word) {
		return hasJongseong(word) ? "이" : "가";
	}

	/** 접속 조사 — 소금빵<b>과</b> · 케이크<b>와</b> */
	public static String conjunction(String word) {
		return hasJongseong(word) ? "과" : "와";
	}
}

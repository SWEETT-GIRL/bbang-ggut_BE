package sweetgirl.croissant.member.util;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 닉네임 유일 비교 · 검색에 쓰는 정규화. DB 마이그레이션(V20261021140000)의
 * {@code lower(regexp_replace(normalize(nickname, NFC), '\s', '', 'g'))} 와 같은 결과여야 한다.
 *
 * <p>iOS 한글 입력은 NFD 로 올 수 있어 NFC 로 모으고, 대소문자 · 공백은 같은 이름으로 본다.
 */
public final class NicknameNormalizer {

	private NicknameNormalizer() {
		// 정적 유틸
	}

	public static String normalize(String nickname) {
		if (nickname == null) {
			return "";
		}
		return Normalizer.normalize(nickname, Normalizer.Form.NFC)
			.replaceAll("\\s", "")
			.toLowerCase(Locale.ROOT);
	}
}

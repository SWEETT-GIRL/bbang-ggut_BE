package sweetgirl.croissant.global.logging;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 앱 세션 id 헤더(#661). 앱이 포그라운드마다 정해 모든 요청에 붙인다 — Amplitude SDK 세션 id(epoch ms 숫자) 또는 앱 UUID.
 * 요청 로그 필터(저장)와 추천 API(조회)가 같은 이름·같은 정규화 규칙을 써야 같은 세션이 같은 값으로 잡힌다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SessionIdHeader {

	public static final String NAME = "X-Session-Id";
	static final int MAX_LENGTH = 64;

	/**
	 * 헤더 값을 저장·조회용으로 다듬는다: 앞뒤 공백 제거, 비어 있거나 출력 가능한 ASCII 가 아니거나 64자를 넘으면 null.
	 * 값은 클라이언트 입력이라 형식을 가정하지 않는다(숫자 문자열·UUID 모두 통과).
	 */
	public static String normalize(String raw) {
		if (raw == null) {
			return null;
		}
		String value = raw.strip();
		if (value.isEmpty() || value.length() > MAX_LENGTH) {
			return null;
		}
		for (int i = 0; i < value.length(); i++) {
			char ch = value.charAt(i);
			if (ch < 0x21 || ch > 0x7E) {
				return null;
			}
		}
		return value;
	}
}

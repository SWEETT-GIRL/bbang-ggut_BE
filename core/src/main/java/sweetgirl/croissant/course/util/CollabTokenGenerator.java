package sweetgirl.croissant.course.util;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 초대 토큰과 공유 토큰(share_token)이 같이 쓰는 생성기. SecureRandom 32B → base64url 43자.
 *
 * 같은 유틸을 쓰되 컬럼은 분리한다 — 초대 토큰은 수락하면 편집자가 되고, 공유 토큰은 보기만 된다.
 * 유출 시 피해 등급이 달라 한 컬럼으로 합치지 않는다.
 */
public final class CollabTokenGenerator {

	public static final int TOKEN_LENGTH = 43;

	private static final int TOKEN_BYTES = 32;
	private static final SecureRandom RANDOM = new SecureRandom();

	private CollabTokenGenerator() {

	}

	public static String generate() {
		byte[] bytes = new byte[TOKEN_BYTES];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}

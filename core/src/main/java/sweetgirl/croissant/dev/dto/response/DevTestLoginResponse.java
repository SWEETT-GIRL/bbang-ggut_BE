package sweetgirl.croissant.dev.dto.response;

/** 게스트 로그인 응답과 같은 모양 — 앱의 토큰 저장 코드를 그대로 쓸 수 있게. */
public record DevTestLoginResponse(
		String accessToken,
		String refreshToken,
		Long memberId,
		String nickname,
		/** 이번 호출로 새로 만들었는가 */
		boolean created) {
}

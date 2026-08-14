package sweetgirl.croissant.member.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class RefreshResponse {

	// 새로 발급된 Access Token
	// 클라이언트는 이 값을 이후 API 요청의 Authorization: Bearer {accessToken} 헤더에 사용
	private String accessToken;

	// 회전된 Refresh Token — 기존 토큰의 남은 수명이 절반 아래일 때만 내려온다(그 외 null).
	// 받은 클라이언트는 저장된 refresh 토큰을 이 값으로 교체한다(슬라이딩 연장).
	// 이 필드를 모르는 구버전 앱은 무시해도 된다 — 기존 토큰도 자연 만료까지 유효하다.
	private String refreshToken;
}

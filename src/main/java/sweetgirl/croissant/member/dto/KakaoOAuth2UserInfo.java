package sweetgirl.croissant.member.dto;

import java.util.Map;

// 카카오 로그인 후 카카오 서버에서 돌려주는 사용자 정보 JSON을 파싱하는 클래스
public class KakaoOAuth2UserInfo {

	// 카카오 응답 최상위 Map (id, kakao_account 등을 담고 있음)
	private final Map<String, Object> attributes;

	// kakao_account 하위 Map (email, profile 등을 담고 있음)
	private final Map<String, Object> kakaoAccount;

	// kakao_account.profile 하위 Map (nickname 등을 담고 있음)
	private final Map<String, Object> profile;

	public KakaoOAuth2UserInfo(Map<String, Object> attributes) {
		this.attributes = attributes;

		// attributes에서 "kakao_account" 키로 중첩 Map 추출
		this.kakaoAccount = (Map<String, Object>)attributes.get("kakao_account");

		// kakao_account에서 "profile" 키로 중첩 Map 추출
		this.profile = (Map<String, Object>)kakaoAccount.get("profile");
	}

	// 카카오 고유 유저 ID 반환 — OAuth 테이블의 providerId로 저장
	public String getProviderId() {
		return String.valueOf(attributes.get("id"));
	}

	// 이메일 반환 — OAuth 테이블의 email로 저장
	public String getEmail() {
		return (String)kakaoAccount.get("email");
	}

	// 닉네임 반환 — Member 테이블의 nickname으로 저장
	public String getNickname() {
		return (String)profile.get("nickname");
	}
}

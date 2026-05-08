package sweetgirl.croissant.member.dto;

import java.util.Collection;
import java.util.Map;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import lombok.Getter;

// 카카오 OAuth2User를 감싸서 우리 DB의 memberId를 함께 보관하는 래퍼 클래스
// loadUser()에서 Member upsert 완료 후 memberId를 여기에 담아 반환
// OAuth2SuccessHandler에서 authentication.getPrincipal()로 꺼내 JWT 생성에 사용
@Getter
public class CustomOAuth2User implements OAuth2User {

	// 원본 카카오 OAuth2User (카카오 응답 속성, 권한 정보 포함)
	private final OAuth2User delegate;

	// 우리 DB Member 테이블 PK — JWT subject로 사용
	private final Long memberId;

	public CustomOAuth2User(OAuth2User delegate, Long memberId) {
		this.delegate = delegate;
		this.memberId = memberId;
	}

	@Override
	public Map<String, Object> getAttributes() {
		return delegate.getAttributes();
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return delegate.getAuthorities();
	}

	@Override
	public String getName() {
		return delegate.getName();
	}
}

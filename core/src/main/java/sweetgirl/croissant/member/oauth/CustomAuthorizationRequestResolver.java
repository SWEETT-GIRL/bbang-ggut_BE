package sweetgirl.croissant.member.oauth;

import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// OAuth2 로그인 시작 시 Spring Security가 호출하는 커스텀 리졸버
// 프론트가 보낸 deviceId를 HttpOnly 임시 쿠키에 저장
// 카카오 로그인은 다른 서버를 거치기 때문에 쿼리 파라미터가 사라지므로,
// 쿠키에 저장해두면 콜백 시 브라우저가 자동으로 쿠키를 같이 보내줘서 deviceId를 복원할 수 있음
public class CustomAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

	// Spring Security 기본 리졸버 — 카카오 인가 URL 조합 등 실제 OAuth2 처리는 여기서 수행
	private final DefaultOAuth2AuthorizationRequestResolver defaultResolver;

	private static final String DEVICE_ID_PARAM = "deviceId"; // 프론트에서 보내는 파라미터 이름
	private static final String DEVICE_ID_COOKIE = "deviceId"; // 임시 저장할 쿠키 이름
	private static final int COOKIE_MAX_AGE = 300; // 쿠키 유효 시간: 5분 (카카오 로그인 완료까지 충분)

	public CustomAuthorizationRequestResolver(ClientRegistrationRepository clientRegistrationRepository) {
		// "/oauth2/authorization" 경로에서 동작하도록 기본 리졸버 초기화
		this.defaultResolver = new DefaultOAuth2AuthorizationRequestResolver(
			clientRegistrationRepository, "/oauth2/authorization");
	}

	// GET /oauth2/authorization/kakao?deviceId=폰고유ID 요청 시 호출
	@Override
	public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
		OAuth2AuthorizationRequest authRequest = defaultResolver.resolve(request);
		if (authRequest != null) {
			saveDeviceIdToCookie(request); // 카카오로 넘어가기 전에 deviceId 쿠키 저장
		}
		return authRequest;
	}

	@Override
	public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
		OAuth2AuthorizationRequest authRequest = defaultResolver.resolve(request, clientRegistrationId);
		if (authRequest != null) {
			saveDeviceIdToCookie(request);
		}
		return authRequest;
	}

	// 요청 파라미터에서 deviceId를 꺼내 HttpOnly 임시 쿠키에 저장
	private void saveDeviceIdToCookie(HttpServletRequest request) {
		String deviceId = request.getParameter(DEVICE_ID_PARAM);
		if (deviceId == null || deviceId.isBlank()) {
			return;
		}

		// OAuth2AuthorizationRequestResolver 인터페이스는 HttpServletResponse를 파라미터로 받지 않음
		// RequestContextHolder를 통해 현재 요청의 Response 객체를 가져옴
		ServletRequestAttributes attrs = (ServletRequestAttributes)RequestContextHolder.getRequestAttributes();
		if (attrs == null) {
			return;
		}
		HttpServletResponse response = attrs.getResponse();
		if (response == null) {
			return;
		}

		Cookie cookie = new Cookie(DEVICE_ID_COOKIE, deviceId);
		cookie.setHttpOnly(true); // JS 접근 차단 (XSS 방어)
		cookie.setPath("/"); // 모든 경로에서 쿠키 전송
		cookie.setMaxAge(COOKIE_MAX_AGE); // 5분 후 자동 삭제
		response.addCookie(cookie);
	}
}

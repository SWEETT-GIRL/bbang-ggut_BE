package sweetgirl.croissant.member.controller;

import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.global.code.GeneralErrorCode;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.dto.response.AppleLoginResponse;
import sweetgirl.croissant.member.dto.response.KakaoLoginResponse;
import sweetgirl.croissant.member.dto.response.RefreshResponse;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.service.AppleAuthService;
import sweetgirl.croissant.member.service.AuthService;
import sweetgirl.croissant.member.service.KakaoAuthService;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

	private MockMvc mockMvc;

	@Mock
	private AuthService authService;

	@Mock
	private KakaoAuthService kakaoAuthService;

	@Mock
	private AppleAuthService appleAuthService;

	@InjectMocks
	private AuthController authController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(authController)
			.setControllerAdvice(new GlobalExceptionHandler()) // CustomException → 401, Validation → 400
			.build();
	}

	// ── POST /auth/kakao ──────────────────────────────────────────────────

	@Test
	void kakaoLogin_returns200WithApiResponseStructure_whenRequestIsValid() throws Exception {
		given(kakaoAuthService.login(eq("kakao.access.token"), eq("device-1"), isNull()))
			.willReturn(new KakaoLoginResponse("new.access", "new.refresh", true));

		mockMvc.perform(post("/auth/kakao")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"accessToken\":\"kakao.access.token\",\"deviceId\":\"device-1\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.code").exists())
			.andExpect(jsonPath("$.message").exists())
			.andExpect(jsonPath("$.data.accessToken").value("new.access"))
			.andExpect(jsonPath("$.data.refreshToken").value("new.refresh"))
			.andExpect(jsonPath("$.data.isNew").value(true));
	}

	@Test
	void kakaoLogin_returns401_whenSocialTokenIsInvalid() throws Exception {
		given(kakaoAuthService.login(eq("invalid-token"), eq("device-1"), isNull()))
			.willThrow(new MemberException(MemberErrorCode.SOCIAL_TOKEN_INVALID));

		mockMvc.perform(post("/auth/kakao")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"accessToken\":\"invalid-token\",\"deviceId\":\"device-1\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("MEMBER401_SOCIAL_TOKEN_INVALID"))
			.andExpect(jsonPath("$.message").value("소셜 로그인 토큰이 유효하지 않습니다."));
	}

	// ── POST /auth/apple ──────────────────────────────────────────────────

	@Test
	void appleLogin_returns200WithApiResponseStructure_whenRequestIsValid() throws Exception {
		given(appleAuthService.login(eq("apple.identity.token"), eq("device-2"), isNull()))
			.willReturn(new AppleLoginResponse("new.access", "new.refresh", false));

		mockMvc.perform(post("/auth/apple")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"identityToken\":\"apple.identity.token\",\"deviceId\":\"device-2\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.code").exists())
			.andExpect(jsonPath("$.message").exists())
			.andExpect(jsonPath("$.data.accessToken").value("new.access"))
			.andExpect(jsonPath("$.data.refreshToken").value("new.refresh"))
			.andExpect(jsonPath("$.data.isNew").value(false));
	}

	@Test
	void appleLogin_returns401_whenSocialTokenIsInvalid() throws Exception {
		given(appleAuthService.login(eq("invalid-token"), eq("device-2"), isNull()))
			.willThrow(new MemberException(MemberErrorCode.SOCIAL_TOKEN_INVALID));

		mockMvc.perform(post("/auth/apple")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"identityToken\":\"invalid-token\",\"deviceId\":\"device-2\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("MEMBER401_SOCIAL_TOKEN_INVALID"))
			.andExpect(jsonPath("$.message").value("소셜 로그인 토큰이 유효하지 않습니다."));
	}

	// ── POST /api/v1/auth/refresh ─────────────────────────────────────────

	@Test
	void refresh_returns200WithAccessToken_whenRequestIsValid() throws Exception {
		given(authService.refresh("valid.token"))
			.willReturn(new RefreshResponse("new.access.token", null));

		mockMvc.perform(post("/auth/refresh")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\":\"valid.token\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.accessToken").value("new.access.token"));
	}

	// 회전이 일어나면 새 refresh 토큰이 응답에 함께 실린다
	@Test
	void refresh_returns200WithRotatedRefreshToken_whenRotated() throws Exception {
		given(authService.refresh("old.token"))
			.willReturn(new RefreshResponse("new.access.token", "rotated.refresh.token"));

		mockMvc.perform(post("/auth/refresh")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\":\"old.token\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.accessToken").value("new.access.token"))
			.andExpect(jsonPath("$.data.refreshToken").value("rotated.refresh.token"));
	}

	@Test
	void refresh_returns400_whenTokenIsEmpty() throws Exception {
		mockMvc.perform(post("/auth/refresh")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\":\"\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void refresh_returns400_whenTokenFieldIsMissing() throws Exception {
		mockMvc.perform(post("/auth/refresh")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void refresh_returns401_whenTokenIsInvalid() throws Exception {
		given(authService.refresh("invalid.token"))
			.willThrow(new CustomException(GeneralErrorCode._UNAUTHORIZED));

		mockMvc.perform(post("/auth/refresh")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\":\"invalid.token\"}"))
			.andExpect(status().isUnauthorized());
	}

	// ── POST /api/v1/auth/logout ──────────────────────────────────────────

	@Test
	void logout_returns200_whenRequestIsValid() throws Exception {
		willDoNothing().given(authService).logout("valid.token", null);

		mockMvc.perform(post("/auth/logout")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\":\"valid.token\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void logout_returns200WithFcmToken_whenRequestIncludesFcmToken() throws Exception {
		willDoNothing().given(authService).logout("valid.token", "fcm-token-value");

		mockMvc.perform(post("/auth/logout")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\":\"valid.token\",\"fcmToken\":\"fcm-token-value\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void logout_returns400_whenTokenIsEmpty() throws Exception {
		mockMvc.perform(post("/auth/logout")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\":\"\"}"))
			.andExpect(status().isBadRequest());
	}
}

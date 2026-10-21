package sweetgirl.croissant.member.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import sweetgirl.croissant.global.contract.ResponseContractWebMvcTest;
import sweetgirl.croissant.member.dto.response.GuestLoginResponse;
import sweetgirl.croissant.member.dto.response.KakaoLoginResponse;
import sweetgirl.croissant.member.dto.response.MemberProfileResponse;
import sweetgirl.croissant.member.dto.response.RefreshResponse;
import sweetgirl.croissant.member.service.AppleAuthService;
import sweetgirl.croissant.member.service.AuthService;
import sweetgirl.croissant.member.service.GuestAuthService;
import sweetgirl.croissant.member.service.KakaoAuthService;
import sweetgirl.croissant.member.service.MemberCommandService;
import sweetgirl.croissant.member.service.MemberQueryService;

/**
 * 앱이 실제로 읽는 로그인·토큰·내 프로필 응답 필드를 고정한다. 토큰 필드가 빠지거나 이름이 바뀌면 로그인·세션 유지가 바로 깨진다.
 * 주석의 파일:라인은 앱 레포 기준 사용처다.
 */
@ContextConfiguration(classes = {AuthController.class, MemberController.class})
class AuthResponseContractTest extends ResponseContractWebMvcTest {

	@MockitoBean
	private AuthService authService;

	@MockitoBean
	private AppleAuthService appleAuthService;

	@MockitoBean
	private KakaoAuthService kakaoAuthService;

	@MockitoBean
	private GuestAuthService guestAuthService;

	@MockitoBean
	private MemberQueryService memberQueryService;

	@MockitoBean
	private MemberCommandService memberCommandService;

	@Test
	void kakaoLogin_keepsFieldsTheAppReads() throws Exception {
		given(kakaoAuthService.login(any(), any(), any()))
			.willReturn(new KakaoLoginResponse("access-token", "refresh-token", true));

		mockMvc.perform(post("/auth/kakao").contentType(MediaType.APPLICATION_JSON)
			.content("{\"accessToken\":\"kakao-token\",\"deviceId\":\"device-1\"}"))
			.andExpect(status().isOk())
			// LoginGateSheet.tsx:433-440 — 없으면 로그인 실패
			.andExpect(jsonPath("$.data.accessToken").isString())
			.andExpect(jsonPath("$.data.refreshToken").isString())
			// LoginGateSheet.tsx:433-440 — 키 이름이 isNew 여야 신규 가입 흐름으로 간다
			.andExpect(jsonPath("$.data.isNew").value(true));
	}

	@Test
	void refresh_keepsFieldsTheAppReads() throws Exception {
		given(authService.refresh("refresh-token"))
			.willReturn(new RefreshResponse("new-access-token", "new-refresh-token"));

		mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\":\"refresh-token\"}"))
			.andExpect(status().isOk())
			// client.ts:239-249 — 없으면 세션이 끊긴다
			.andExpect(jsonPath("$.data.accessToken").isString())
			// client.ts:239-249 — 없으면 기존 토큰을 계속 쓴다
			.andExpect(jsonPath("$.data.refreshToken").isString());
	}

	@Test
	void guestLogin_keepsFieldsTheAppReads() throws Exception {
		given(guestAuthService.login("device-1"))
			.willReturn(new GuestLoginResponse("access-token", "refresh-token", 7L, "고소한 소보로"));

		mockMvc.perform(post("/auth/guest").contentType(MediaType.APPLICATION_JSON)
			.content("{\"deviceId\":\"device-1\"}"))
			.andExpect(status().isOk())
			// LoginGateSheet.tsx:234-240, useBootstrap.ts:42
			.andExpect(jsonPath("$.data.accessToken").isString())
			.andExpect(jsonPath("$.data.refreshToken").isString())
			.andExpect(jsonPath("$.data.memberId").isNumber())
			.andExpect(jsonPath("$.data.nickname").isString());
	}

	@Test
	void myProfile_keepsFieldsTheAppReads() throws Exception {
		given(memberQueryService.getProfile(any())).willReturn(new MemberProfileResponse(7L, "고소한 소보로", null));

		mockMvc.perform(get("/users/me"))
			.andExpect(status().isOk())
			// LoginGateSheet.tsx:315-318
			.andExpect(jsonPath("$.data.memberId").isNumber())
			.andExpect(jsonPath("$.data.nickname").isString());
	}
}

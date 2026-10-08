package sweetgirl.croissant.breadlog.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import sweetgirl.croissant.breadlog.dto.response.BreadRecordActivityResponse;
import sweetgirl.croissant.breadlog.service.BreadRecordActivityQueryService;
import sweetgirl.croissant.config.security.ApiAuthenticationEntryPoint;
import sweetgirl.croissant.config.security.SecurityConfig;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.jwt.JwtProvider;
import sweetgirl.croissant.member.oauth.OAuth2SuccessHandler;
import sweetgirl.croissant.member.service.CustomOAuth2UserService;

@WebMvcTest(properties = "oauth2.front-redirect-uri=bbanggut://test")
@ContextConfiguration(classes = {BreadRecordActivityController.class, SecurityConfig.class,
	ApiAuthenticationEntryPoint.class, GlobalExceptionHandler.class})
class BreadRecordActivityControllerTest {

	@MockitoBean
	BreadRecordActivityQueryService queryService;
	@MockitoBean
	JwtProvider jwtProvider;
	@MockitoBean
	CustomOAuth2UserService oauthUserService;
	@MockitoBean
	OAuth2SuccessHandler oauthSuccessHandler;
	@MockitoBean
	ClientRegistrationRepository registrations;
	@Autowired
	MockMvc mvc;

	@Test
	void anonymousCannotReadActivity() throws Exception {
		mvc.perform(get("/bread-records/activity")).andExpect(status().isUnauthorized());
		verifyNoInteractions(queryService);
	}

	@Test
	void guestTokenReadsOwnActivityWithOffsetAndPolicy() throws Exception {
		authenticate();
		when(queryService.getActivity(1L)).thenReturn(new BreadRecordActivityResponse(
			LocalDate.parse("2026-10-09"), "Asia/Seoul", OffsetDateTime.parse("2026-10-09T00:00:00+09:00"),
			false, 1, 2, 1, 0, LocalDate.parse("2026-10-08"), null, null,
			BreadRecordActivityResponse.POLICY_VERSION));
		mvc.perform(get("/bread-records/activity").header("Authorization", "Bearer guest-token"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.currentStreakDays").value(1))
			.andExpect(jsonPath("$.data.nextStreakDays").value(2))
			.andExpect(jsonPath("$.data.recordedToday").value(false))
			.andExpect(jsonPath("$.data.computedAt").value("2026-10-09T00:00:00+09:00"))
			.andExpect(jsonPath("$.data.policyVersion").value(BreadRecordActivityResponse.POLICY_VERSION));
	}

	@Test
	void deletedMemberIsRejected() throws Exception {
		authenticate();
		when(queryService.getActivity(1L)).thenThrow(new MemberException(MemberErrorCode.MEMBER_DELETED));
		mvc.perform(get("/bread-records/activity").header("Authorization", "Bearer guest-token"))
			.andExpect(status().is4xxClientError());
	}

	private void authenticate() {
		when(jwtProvider.validateToken("guest-token")).thenReturn(true);
		when(jwtProvider.getMemberId("guest-token")).thenReturn(1L);
	}
}

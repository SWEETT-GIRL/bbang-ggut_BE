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
import sweetgirl.croissant.member.service.AuthService;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

	private MockMvc mockMvc;

	@Mock
	private AuthService authService;

	@InjectMocks
	private AuthController authController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(authController)
			.setControllerAdvice(new GlobalExceptionHandler()) // CustomException → 401, Validation → 400
			.build();
	}

	// ── POST /api/v1/auth/refresh ─────────────────────────────────────────

	@Test
	void refresh_returns200WithAccessToken_whenRequestIsValid() throws Exception {
		given(authService.refresh("valid.token")).willReturn("new.access.token");

		mockMvc.perform(post("/api/v1/auth/refresh")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\":\"valid.token\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.accessToken").value("new.access.token"));
	}

	@Test
	void refresh_returns400_whenTokenIsEmpty() throws Exception {
		mockMvc.perform(post("/api/v1/auth/refresh")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\":\"\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void refresh_returns400_whenTokenFieldIsMissing() throws Exception {
		mockMvc.perform(post("/api/v1/auth/refresh")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void refresh_returns401_whenTokenIsInvalid() throws Exception {
		given(authService.refresh("invalid.token"))
			.willThrow(new CustomException(GeneralErrorCode._UNAUTHORIZED));

		mockMvc.perform(post("/api/v1/auth/refresh")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\":\"invalid.token\"}"))
			.andExpect(status().isUnauthorized());
	}

	// ── POST /api/v1/auth/logout ──────────────────────────────────────────

	@Test
	void logout_returns200_whenRequestIsValid() throws Exception {
		willDoNothing().given(authService).logout("valid.token");

		mockMvc.perform(post("/api/v1/auth/logout")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\":\"valid.token\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void logout_returns400_whenTokenIsEmpty() throws Exception {
		mockMvc.perform(post("/api/v1/auth/logout")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\":\"\"}"))
			.andExpect(status().isBadRequest());
	}
}

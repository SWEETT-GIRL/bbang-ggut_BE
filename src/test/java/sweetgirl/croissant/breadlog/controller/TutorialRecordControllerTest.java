package sweetgirl.croissant.breadlog.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import sweetgirl.croissant.breadlog.dto.response.TutorialRecordResponse;
import sweetgirl.croissant.breadlog.service.TutorialRecordCommandService;
import sweetgirl.croissant.breadlog.service.TutorialRecordQueryService;
import sweetgirl.croissant.config.security.ApiAuthenticationEntryPoint;
import sweetgirl.croissant.config.security.SecurityConfig;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.member.jwt.JwtProvider;
import sweetgirl.croissant.member.oauth.OAuth2SuccessHandler;
import sweetgirl.croissant.member.service.CustomOAuth2UserService;

@WebMvcTest(properties = "oauth2.front-redirect-uri=bbanggut://test")
@ContextConfiguration(classes = {TutorialRecordController.class, SecurityConfig.class,
	ApiAuthenticationEntryPoint.class, GlobalExceptionHandler.class})
class TutorialRecordControllerTest {
	@MockitoBean
	TutorialRecordCommandService commandService;
	@MockitoBean
	TutorialRecordQueryService queryService;
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
	private static final String BODY = """
			{"templateCode":"bbangdaengi-v1","eatenAt":"2026-10-08T12:00:00"}
			""";

	private void authenticate() {
		when(jwtProvider.validateToken("guest-token")).thenReturn(true);
		when(jwtProvider.getMemberId("guest-token")).thenReturn(1L);
	}

	@Test
	void anonymousCannotReadCreateOrEdit() throws Exception {
		mvc.perform(get("/bread-records/tutorial")).andExpect(status().isUnauthorized());
		mvc.perform(post("/bread-records/tutorial").contentType(MediaType.APPLICATION_JSON).content(BODY))
			.andExpect(status().isUnauthorized());
		mvc.perform(patch("/bread-records/tutorial/10").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized());
		verifyNoInteractions(commandService, queryService);
	}

	@Test
	void firstCreationReturns201AndRetryReturns200() throws Exception {
		authenticate();
		when(commandService.complete(eq(1L), any()))
			.thenReturn(new TutorialRecordResponse(true, true, 10L, false))
			.thenReturn(new TutorialRecordResponse(false, true, 10L, false));
		mvc.perform(post("/bread-records/tutorial").header("Authorization", "Bearer guest-token")
			.contentType(MediaType.APPLICATION_JSON).content(BODY))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.data.recordId").value(10));
		mvc.perform(post("/bread-records/tutorial").header("Authorization", "Bearer guest-token")
			.contentType(MediaType.APPLICATION_JSON).content(BODY))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.created").value(false));
	}

	@Test
	void rejectsMissingDate() throws Exception {
		authenticate();
		mvc.perform(post("/bread-records/tutorial").header("Authorization", "Bearer guest-token")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"templateCode\":\"bbangdaengi-v1\"}")).andExpect(status().isBadRequest());
		verifyNoInteractions(commandService);
	}

	@Test
	void rejectsMissingTemplate() throws Exception {
		authenticate();
		mvc.perform(post("/bread-records/tutorial").header("Authorization", "Bearer guest-token")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")).andExpect(status().isBadRequest());
	}

	@Test
	void editUsesDedicatedContract() throws Exception {
		authenticate();
		mvc.perform(patch("/bread-records/tutorial/10").header("Authorization", "Bearer guest-token")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"memo\":\"좋아\",\"towerBread\":\"bagel\"}")).andExpect(status().isOk());
		verify(commandService).edit(eq(1L), eq(10L), any());
	}
}

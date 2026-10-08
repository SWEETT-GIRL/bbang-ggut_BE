package sweetgirl.croissant.admin.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.admin.service.AdminTutorialTemplateCommandService;
import sweetgirl.croissant.breadlog.TutorialTemplateFixture;
import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.service.TutorialTemplateQueryService;
import sweetgirl.croissant.config.security.ApiAuthenticationEntryPoint;
import sweetgirl.croissant.config.security.SecurityConfig;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.letter.config.AdminApiKeyConfig;
import sweetgirl.croissant.member.jwt.JwtProvider;
import sweetgirl.croissant.member.oauth.OAuth2SuccessHandler;
import sweetgirl.croissant.member.service.CustomOAuth2UserService;

@WebMvcTest(properties = {"oauth2.front-redirect-uri=bbanggut://test", "admin.api-key=test-secret"})
@ContextConfiguration(classes = {AdminTutorialTemplateController.class, AdminApiKeyConfig.class,
	SecurityConfig.class, ApiAuthenticationEntryPoint.class, GlobalExceptionHandler.class})
class AdminTutorialTemplateControllerTest {
	@MockitoBean
	AdminTutorialTemplateCommandService commands;
	@MockitoBean
	TutorialTemplateQueryService queries;
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
	private static final String ROOT = "/api/admin/tutorial-templates";

	@Test
	void protectsRootAndEveryMutationWithAdminKey() throws Exception {
		mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());
		for (String path : List.of("", "/upload-url", "/sample/publish", "/stop")) {
			mvc.perform(post(ROOT + path).header("X-Admin-Key", "wrong")
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());
		}
		verifyNoInteractions(commands, queries);
	}

	@Test
	void authorizedAdminCanListSaveUploadPublishAndStop() throws Exception {
		when(queries.active()).thenReturn(Optional.empty());
		when(queries.list()).thenReturn(List.of());
		when(commands.saveDraft(any())).thenReturn("draft-code");
		when(commands.upload("image/png")).thenReturn(new AdminTutorialTemplateCommandService.Upload(
			"onboarding/tutorials/photo.png", "https://storage.example/upload", Map.of("If-None-Match", "*")));
		mvc.perform(get(ROOT).header("X-Admin-Key", "test-secret"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.revisions").isEmpty());
		mvc.perform(post(ROOT).header("X-Admin-Key", "test-secret").contentType(MediaType.APPLICATION_JSON)
			.content(new ObjectMapper().writeValueAsString(TutorialTemplateFixture.content())))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.code").value("draft-code"));
		mvc.perform(post(ROOT + "/upload-url").header("X-Admin-Key", "test-secret")
			.contentType(MediaType.APPLICATION_JSON).content("{\"contentType\":\"image/png\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.headers.If-None-Match").value("*"));
		for (String path : List.of("/draft-code/publish", "/stop")) {
			mvc.perform(post(ROOT + path).header("X-Admin-Key", "test-secret")
				.contentType(MediaType.APPLICATION_JSON).content("{\"expectedActiveCode\":null}"))
				.andExpect(status().isOk());
		}
		verify(commands).publish("draft-code", null);
		verify(commands).stop(null);
	}

	@Test
	void rejectsIncompleteTemplateAndReturnsConflictForStalePublish() throws Exception {
		mvc.perform(post(ROOT).header("X-Admin-Key", "test-secret").contentType(MediaType.APPLICATION_JSON)
			.content("{}")).andExpect(status().isBadRequest());
		verify(commands, never()).saveDraft(any());
		doThrow(new BreadLogException(BreadLogErrorCode.TUTORIAL_CATALOG_CONFLICT))
			.when(commands).publish("draft", "old");
		mvc.perform(post(ROOT + "/draft/publish").header("X-Admin-Key", "test-secret")
			.contentType(MediaType.APPLICATION_JSON).content("{\"expectedActiveCode\":\"old\"}"))
			.andExpect(status().isConflict());
	}
}

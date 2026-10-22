package sweetgirl.croissant.dev.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.dev.dto.response.DevTestLoginResponse;
import sweetgirl.croissant.dev.service.DevAuthService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DevAuthController")
class DevAuthControllerTest {

	private static final String KEY = "dev-key";
	private static final String BODY = "{\"nickname\":\"테스트빵순이\"}";

	private MockMvc mockMvc;

	@Mock
	private DevAuthService devAuthService;
	@Mock
	private Environment environment;

	@InjectMocks
	private DevAuthController controller;

	private void setUpWith(String[] profiles, String configuredKey) {
		given(environment.getActiveProfiles()).willReturn(profiles);
		ReflectionTestUtils.setField(controller, "testLoginKey", configuredKey);
		mockMvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
		given(devAuthService.login(eq("테스트빵순이"), any()))
			.willReturn(new DevTestLoginResponse("access", "refresh", 10L, "테스트빵순이", true));
	}

	@Test
	@DisplayName("local 에서는 키 없이 토큰을 준다")
	void localNoKey() throws Exception {
		setUpWith(new String[] {"local"}, "");

		mockMvc.perform(post("/dev/auth/test-login").contentType(MediaType.APPLICATION_JSON).content(BODY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.memberId").value(10))
			.andExpect(jsonPath("$.data.accessToken").value("access"));
	}

	@Test
	@DisplayName("staging 에서는 키가 맞아야 하고, 틀리거나 서버에 키가 비어 있으면 403")
	void stagingNeedsKey() throws Exception {
		setUpWith(new String[] {"staging"}, KEY);
		mockMvc.perform(post("/dev/auth/test-login").header("X-Dev-Test-Key", KEY)
			.contentType(MediaType.APPLICATION_JSON).content(BODY))
			.andExpect(status().isOk());
		mockMvc.perform(post("/dev/auth/test-login").header("X-Dev-Test-Key", "wrong")
			.contentType(MediaType.APPLICATION_JSON).content(BODY))
			.andExpect(status().isForbidden());

		setUpWith(new String[] {"staging"}, "");
		mockMvc.perform(post("/dev/auth/test-login").header("X-Dev-Test-Key", KEY)
			.contentType(MediaType.APPLICATION_JSON).content(BODY))
			.andExpect(status().isForbidden());
	}
}

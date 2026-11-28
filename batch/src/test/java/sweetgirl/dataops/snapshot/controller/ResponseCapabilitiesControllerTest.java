package sweetgirl.dataops.snapshot.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import sweetgirl.dataops.autoapply.AutomaticApplyReadinessQueryService;
import sweetgirl.dataops.config.security.DataopsSecurityConfig;

@WebMvcTest(properties = {"spring.config.name=dataops", "spring.main.web-application-type=servlet", "dataops.auto-apply.enabled=true", "dataops.api-key=test-key"})
@ContextConfiguration(classes = {ResponseCapabilitiesController.class, DataopsSecurityConfig.class})
@ImportAutoConfiguration({SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
class ResponseCapabilitiesControllerTest {
	@Autowired private MockMvc mvc;
	@MockitoBean private AutomaticApplyReadinessQueryService readiness;

	@Test void permission_readiness_is_additive_and_requires_the_api_key() throws Exception {
		mvc.perform(get("/api/snapshots/capabilities")).andExpect(status().isUnauthorized());
		verifyNoInteractions(readiness);
		when(readiness.hoursPermissionsReady()).thenReturn(false);
		mvc.perform(get("/api/snapshots/capabilities").header("X-Dataops-Key", "test-key"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.captureProtocol").value(1))
			.andExpect(jsonPath("$.data.writesEnabled").value(false))
			.andExpect(jsonPath("$.data.automaticApplyProtocol").value(1))
			.andExpect(jsonPath("$.data.automaticHoursPermissionsReady").value(false));
		when(readiness.hoursPermissionsReady()).thenReturn(true);
		mvc.perform(get("/api/snapshots/capabilities").header("X-Dataops-Key", "test-key"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.automaticHoursPermissionsReady").value(true));
	}
}

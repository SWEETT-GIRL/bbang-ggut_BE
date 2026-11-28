package sweetgirl.dataops.autoapply;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import sweetgirl.dataops.config.security.DataopsSecurityConfig;

@WebMvcTest(properties = {"spring.config.name=dataops", "spring.main.web-application-type=servlet", "dataops.auto-apply.enabled=true", "dataops.api-key=test-key"})
@ContextConfiguration(classes = {AutomaticApplyController.class, DataopsSecurityConfig.class})
@ImportAutoConfiguration({SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
class AutomaticApplyControllerTest {
	@Autowired private MockMvc mvc;
	@MockitoBean private AutomaticApplyCommandService service;

	@Test void returns_the_existing_response_contract_for_the_exact_capture() throws Exception {
		when(service.apply("capture-1")).thenReturn(new AutomaticApplyResponseDto("capture-1", 2, 1, 1, 0, 0,
			"SOURCE_CONFLICT", List.of(new AutomaticApplyItemResponseDto(7, "HELD", "SOURCE_CONFLICT"))));
		mvc.perform(post("/api/snapshots/capture-1/auto-apply").header("X-Dataops-Key", "test-key"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.captureId").value("capture-1"))
			.andExpect(jsonPath("$.data.applied").value(2)).andExpect(jsonPath("$.data.alreadyApplied").value(1))
			.andExpect(jsonPath("$.data.held").value(1)).andExpect(jsonPath("$.data.failed").value(0))
			.andExpect(jsonPath("$.data.unknown").value(0)).andExpect(jsonPath("$.data.observationHold").value("SOURCE_CONFLICT"))
			.andExpect(jsonPath("$.data.items[0].proposalId").value(7))
			.andExpect(jsonPath("$.data.items[0].status").value("HELD"))
			.andExpect(jsonPath("$.data.items[0].reason").value("SOURCE_CONFLICT"));
		verify(service).apply("capture-1");
	}

	@Test void missing_or_wrong_api_key_never_invokes_the_writer() throws Exception {
		mvc.perform(post("/api/snapshots/capture-1/auto-apply")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/snapshots/capture-1/auto-apply").header("X-Dataops-Key", "wrong"))
			.andExpect(status().isUnauthorized());
		verifyNoInteractions(service);
	}
}

@WebMvcTest(properties = {"spring.config.name=dataops", "spring.main.web-application-type=servlet", "dataops.auto-apply.enabled=false", "dataops.api-key=test-key"})
@ContextConfiguration(classes = {AutomaticApplyController.class, DataopsSecurityConfig.class})
@ImportAutoConfiguration({SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
class AutomaticApplyDisabledControllerTest {
	@Autowired private MockMvc mvc;
	@MockitoBean private AutomaticApplyCommandService service;

	@Test void disabled_mode_does_not_register_the_write_endpoint() throws Exception {
		mvc.perform(post("/api/snapshots/capture-1/auto-apply").header("X-Dataops-Key", "test-key"))
			.andExpect(status().isNotFound());
		verifyNoInteractions(service);
	}
}

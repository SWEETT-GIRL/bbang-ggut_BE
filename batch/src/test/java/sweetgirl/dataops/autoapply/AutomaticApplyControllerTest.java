package sweetgirl.dataops.autoapply;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AutomaticApplyControllerTest {
	@Test void returns_separate_apply_counts_for_the_exact_capture() throws Exception {
		var service = mock(AutomaticApplyService.class);
		when(service.apply("capture-1")).thenReturn(new AutomaticApplyService.Result("capture-1", 2, 0, 1, 0, 0, null, List.of()));
		MockMvcBuilders.standaloneSetup(new AutomaticApplyController(service)).build()
			.perform(post("/api/snapshots/capture-1/auto-apply"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.captureId").value("capture-1"))
			.andExpect(jsonPath("$.data.applied").value(2)).andExpect(jsonPath("$.data.held").value(1));
	}
}

package sweetgirl.croissant.global.advice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

class GlobalExceptionHandlerTest {

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(new TestController())
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void missingRequiredParam_returns400() throws Exception {
		mockMvc.perform(get("/test/require-keyword"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("COMMON400"));
	}

	@Test
	void typeMismatchParam_returns400() throws Exception {
		mockMvc.perform(get("/test/require-number").param("number", "not-a-number"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("COMMON400"));
	}

	@Test
	void asyncRequestNotUsableException_returnsNoErrorResponse() {
		GlobalExceptionHandler handler = new GlobalExceptionHandler();

		handler.handleAsyncRequestNotUsableException(new AsyncRequestNotUsableException(
			"ServletResponse failed to flushBuffer: java.io.IOException: Broken pipe"));
	}

	@RestController
	static class TestController {

		@GetMapping("/test/require-keyword")
		String requireKeyword(@RequestParam String keyword) {
			return keyword;
		}

		@GetMapping("/test/require-number")
		String requireNumber(@RequestParam int number) {
			return String.valueOf(number);
		}
	}
}

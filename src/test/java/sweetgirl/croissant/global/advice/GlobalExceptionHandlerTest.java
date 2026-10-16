package sweetgirl.croissant.global.advice;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import jakarta.servlet.http.HttpServletRequest;
import sweetgirl.croissant.global.alert.ServerErrorAlerter;
import sweetgirl.croissant.global.code.GeneralErrorCode;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.global.logging.filter.RequestLoggingFilter;
import sweetgirl.croissant.global.response.ApiResponse;

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

	@Test
	void httpMessageNotWritableExceptionWithBrokenPipe_returnsNoErrorResponse() {
		GlobalExceptionHandler handler = new GlobalExceptionHandler();

		ResponseEntity<ApiResponse<?>> response = handler.handleHttpMessageNotWritableException(
			new HttpMessageNotWritableException("Could not write JSON: ServletOutputStream failed to write",
				new IOException("Broken pipe")),
			null);

		assertThat(response).isNull();
	}

	@Test
	void httpMessageNotWritableExceptionWithoutClientAbort_returns500() {
		GlobalExceptionHandler handler = new GlobalExceptionHandler();
		HttpServletRequest request = mock(HttpServletRequest.class);

		ResponseEntity<ApiResponse<?>> response = handler.handleHttpMessageNotWritableException(
			new HttpMessageNotWritableException("Could not write JSON", new IllegalStateException("bad json")),
			request);

		assertThat(response.getStatusCode().is5xxServerError()).isTrue();
	}

	@Test
	void maxUploadSizeExceededException_returns413() {
		GlobalExceptionHandler handler = new GlobalExceptionHandler();
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getMethod()).thenReturn("POST");
		when(request.getRequestURI()).thenReturn("/bread-records/photos/analyze");

		ResponseEntity<ApiResponse<?>> response = handler.handleMaxUploadSizeExceededException(
			new MaxUploadSizeExceededException(5_242_880L), request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
		assertThat(response.getBody().getCode()).isEqualTo("COMMON413");
		assertThat(response.getBody().isSuccess()).isFalse();
	}

	@Test
	void maxUploadSizeExceededException_recordsErrorCodeForRequestLog() {
		GlobalExceptionHandler handler = new GlobalExceptionHandler();
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getMethod()).thenReturn("POST");
		when(request.getRequestURI()).thenReturn("/bread-records/photos/analyze");

		handler.handleMaxUploadSizeExceededException(new MaxUploadSizeExceededException(5_242_880L), request);

		verify(request).setAttribute(RequestLoggingFilter.ATTR_ERROR_CODE, "COMMON413");
	}

	@Test
	void unhandledException_alertsServerError() {
		ServerErrorAlerter alerter = mock(ServerErrorAlerter.class);
		GlobalExceptionHandler handler = new GlobalExceptionHandler(alerter);
		HttpServletRequest request = mock(HttpServletRequest.class);
		IllegalStateException exception = new IllegalStateException("boom");

		ResponseEntity<ApiResponse<?>> response = handler.handleGenericException(exception, request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		verify(alerter).alert(request, 500, exception);
	}

	@Test
	void customException_alertsOnlyWhenServerError() {
		ServerErrorAlerter alerter = mock(ServerErrorAlerter.class);
		GlobalExceptionHandler handler = new GlobalExceptionHandler(alerter);
		HttpServletRequest request = mock(HttpServletRequest.class);
		CustomException clientError = new CustomException(GeneralErrorCode._BAD_REQUEST);
		CustomException serverError = new CustomException(GeneralErrorCode._INTERNAL_SERVER_ERROR);

		handler.handleCustomException(clientError, request);
		handler.handleCustomException(serverError, request);

		verify(alerter, never()).alert(request, 400, clientError);
		verify(alerter).alert(request, 500, serverError);
	}

	@Test
	void clientAbort_doesNotAlert() {
		ServerErrorAlerter alerter = mock(ServerErrorAlerter.class);
		GlobalExceptionHandler handler = new GlobalExceptionHandler(alerter);

		handler.handleGenericException(new IOException("Broken pipe"), mock(HttpServletRequest.class));

		verifyNoInteractions(alerter);
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

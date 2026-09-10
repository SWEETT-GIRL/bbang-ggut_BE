package sweetgirl.croissant.global.advice;

import java.io.IOException;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.global.code.GeneralErrorCode;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.global.logging.filter.RequestLoggingFilter;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.global.response.ValidationErrorResponse;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final String CLIENT_ABORT_EXCEPTION_CLASS_NAME = "ClientAbortException";
	private static final String BROKEN_PIPE_MESSAGE = "broken pipe";
	private static final String CONNECTION_RESET_MESSAGE = "connection reset";
	private static final String STREAM_CLOSED_MESSAGE = "stream closed";

	@ExceptionHandler(CustomException.class)
	public ResponseEntity<ApiResponse<?>> handleCustomException(CustomException ex, HttpServletRequest request) {
		log.warn("[CustomException] code={}, message={}", ex.getErrorCode().getCode(), ex.getMessage());
		request.setAttribute(RequestLoggingFilter.ATTR_ERROR_CODE, ex.getErrorCode().getCode());
		request.setAttribute(RequestLoggingFilter.ATTR_ERROR_MESSAGE, ex.getMessage());
		return ResponseEntity
			.status(ex.getHttpStatus())
			.body(ApiResponse.fail(ex.getErrorCode(), ex.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<?>> handleValidationException(MethodArgumentNotValidException ex,
			HttpServletRequest request) {
		ValidationErrorResponse validationErrorResponse = ValidationErrorResponse.from(
			ex.getBindingResult().getFieldErrors());
		log.warn("[ValidationException] errors={}", ex.getBindingResult().getFieldErrors());
		request.setAttribute(RequestLoggingFilter.ATTR_ERROR_CODE, GeneralErrorCode._BAD_REQUEST.getCode());
		request.setAttribute(RequestLoggingFilter.ATTR_ERROR_MESSAGE,
			ex.getBindingResult().getFieldErrors().toString());
		return ResponseEntity
			.status(HttpStatus.BAD_REQUEST)
			.body(ApiResponse.fail(
				GeneralErrorCode._BAD_REQUEST,
				GeneralErrorCode._BAD_REQUEST.getMessage(),
				validationErrorResponse));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiResponse<?>> handleHttpMessageNotReadableException(HttpMessageNotReadableException ex,
			HttpServletRequest request) {
		log.warn("[HttpMessageNotReadableException] {}", ex.getMessage());
		request.setAttribute(RequestLoggingFilter.ATTR_ERROR_CODE, GeneralErrorCode._BAD_REQUEST.getCode());
		request.setAttribute(RequestLoggingFilter.ATTR_ERROR_MESSAGE, ex.getMessage());
		return ResponseEntity
			.status(HttpStatus.BAD_REQUEST)
			.body(ApiResponse.fail(GeneralErrorCode._BAD_REQUEST));
	}

	@ExceptionHandler(HttpMessageNotWritableException.class)
	public ResponseEntity<ApiResponse<?>> handleHttpMessageNotWritableException(HttpMessageNotWritableException ex,
			HttpServletRequest request) {
		if (isClientAbortException(ex)) {
			log.debug("[ClientAbort] {}", ex.getMessage());
			return null;
		}
		return handleGenericException(ex, request);
	}

	@ExceptionHandler({ServletRequestBindingException.class, MethodArgumentTypeMismatchException.class})
	public ResponseEntity<ApiResponse<?>> handleRequestBindingException(Exception ex, HttpServletRequest request) {
		log.warn("[RequestBindingException] {}", ex.getMessage());
		request.setAttribute(RequestLoggingFilter.ATTR_ERROR_CODE, GeneralErrorCode._BAD_REQUEST.getCode());
		request.setAttribute(RequestLoggingFilter.ATTR_ERROR_MESSAGE, ex.getMessage());
		return ResponseEntity
			.status(HttpStatus.BAD_REQUEST)
			.body(ApiResponse.fail(GeneralErrorCode._BAD_REQUEST));
	}

	@ExceptionHandler(EntityNotFoundException.class)
	public ResponseEntity<ApiResponse<?>> handleEntityNotFoundException(EntityNotFoundException ex,
			HttpServletRequest request) {
		log.warn("[EntityNotFoundException] {}", ex.getMessage());
		request.setAttribute(RequestLoggingFilter.ATTR_ERROR_CODE, GeneralErrorCode._NOT_FOUND.getCode());
		request.setAttribute(RequestLoggingFilter.ATTR_ERROR_MESSAGE, ex.getMessage());
		return ResponseEntity
			.status(HttpStatus.NOT_FOUND)
			.body(ApiResponse.fail(GeneralErrorCode._NOT_FOUND));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ApiResponse<?>> handleIllegalArgumentException(IllegalArgumentException ex,
			HttpServletRequest request) {
		log.warn("[IllegalArgumentException] {}", ex.getMessage());
		request.setAttribute(RequestLoggingFilter.ATTR_ERROR_CODE, GeneralErrorCode._BAD_REQUEST.getCode());
		request.setAttribute(RequestLoggingFilter.ATTR_ERROR_MESSAGE, ex.getMessage());
		return ResponseEntity
			.status(HttpStatus.BAD_REQUEST)
			.body(ApiResponse.fail(GeneralErrorCode._BAD_REQUEST));
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ApiResponse<?>> handleNoResourceFoundException(NoResourceFoundException ex,
			HttpServletRequest request) {
		log.warn("[NoResourceFoundException] {}", ex.getMessage());
		request.setAttribute(RequestLoggingFilter.ATTR_ERROR_CODE, GeneralErrorCode._NOT_FOUND.getCode());
		request.setAttribute(RequestLoggingFilter.ATTR_ERROR_MESSAGE, ex.getMessage());
		return ResponseEntity
			.status(HttpStatus.NOT_FOUND)
			.body(ApiResponse.fail(GeneralErrorCode._NOT_FOUND));
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ApiResponse<?>> handleMethodNotAllowedException(HttpRequestMethodNotSupportedException ex,
			HttpServletRequest request) {
		log.warn("[MethodNotAllowedException] {}", ex.getMessage());
		request.setAttribute(RequestLoggingFilter.ATTR_ERROR_CODE, GeneralErrorCode._METHOD_NOT_ALLOWED.getCode());
		request.setAttribute(RequestLoggingFilter.ATTR_ERROR_MESSAGE, ex.getMessage());
		return ResponseEntity
			.status(HttpStatus.METHOD_NOT_ALLOWED)
			.body(ApiResponse.fail(GeneralErrorCode._METHOD_NOT_ALLOWED));
	}

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<ApiResponse<?>> handleResponseStatusException(ResponseStatusException ex,
			HttpServletRequest request) {
		GeneralErrorCode errorCode = resolveGeneralErrorCode(ex);
		String message = ex.getReason() != null ? ex.getReason() : errorCode.getMessage();
		log.warn("[ResponseStatusException] status={}, reason={}", ex.getStatusCode(), message);
		request.setAttribute(RequestLoggingFilter.ATTR_ERROR_CODE, errorCode.getCode());
		request.setAttribute(RequestLoggingFilter.ATTR_ERROR_MESSAGE, message);
		return ResponseEntity
			.status(ex.getStatusCode())
			.body(ApiResponse.fail(errorCode, message));
	}

	@ExceptionHandler(AsyncRequestNotUsableException.class)
	public void handleAsyncRequestNotUsableException(AsyncRequestNotUsableException ex) {
		log.debug("[ClientAbort] {}", ex.getMessage());
	}

	/**
	 * 멀티파트 본문이 크기 상한을 넘어 파싱 단계에서 끊긴 경우.
	 *
	 * <p>DispatcherServlet 이 컨트롤러를 부르기 전에 던지므로 서비스 계층의 크기 검증으로는 잡히지 않는다 —
	 * {@code breadlog.photo-tag.max-photo-size} 처럼 상한이 멀티파트 설정과 같으면 그쪽은 도달조차 못 한다.
	 *
	 * <p>클라이언트가 줄여 보내면 되는 잘못이라 500 이 아니라 413 이다. 500 으로 두면 서버 장애 알림에
	 * 섞여 진짜 장애를 가린다.
	 */
	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<ApiResponse<?>> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
			HttpServletRequest request) {
		log.warn("[MaxUploadSizeExceeded] method={}, uri={}, maxUploadSize={}",
			request.getMethod(), request.getRequestURI(), ex.getMaxUploadSize());
		request.setAttribute(RequestLoggingFilter.ATTR_ERROR_CODE, GeneralErrorCode._PAYLOAD_TOO_LARGE.getCode());
		request.setAttribute(RequestLoggingFilter.ATTR_ERROR_MESSAGE, ex.getMessage());
		return ResponseEntity
			.status(HttpStatus.PAYLOAD_TOO_LARGE)
			.body(ApiResponse.fail(GeneralErrorCode._PAYLOAD_TOO_LARGE));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<?>> handleGenericException(Exception ex, HttpServletRequest request) {
		if (isClientAbortException(ex)) {
			log.debug("[ClientAbort] {}", ex.getMessage());
			return null;
		}
		log.error("[UnhandledException] method={}, uri={}, message={}",
			request.getMethod(), request.getRequestURI(), ex.getMessage(), ex);
		request.setAttribute(RequestLoggingFilter.ATTR_ERROR_CODE, "INTERNAL_SERVER_ERROR");
		request.setAttribute(RequestLoggingFilter.ATTR_ERROR_MESSAGE, ex.getMessage());
		return ResponseEntity
			.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(ApiResponse.fail(GeneralErrorCode._INTERNAL_SERVER_ERROR));
	}

	private boolean isClientAbortException(Throwable ex) {
		Throwable current = ex;
		while (current != null) {
			if (isClientAbortClass(current) || isClientAbortIoException(current) || hasClientAbortMessage(current)) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private boolean isClientAbortClass(Throwable ex) {
		return ex.getClass().getSimpleName().contains(CLIENT_ABORT_EXCEPTION_CLASS_NAME);
	}

	private boolean isClientAbortIoException(Throwable ex) {
		return ex instanceof IOException && hasClientAbortMessage(ex);
	}

	private boolean hasClientAbortMessage(Throwable ex) {
		String message = ex.getMessage();
		if (message == null) {
			return false;
		}
		String lowerCaseMessage = message.toLowerCase(Locale.ROOT);
		return lowerCaseMessage.contains(BROKEN_PIPE_MESSAGE)
			|| lowerCaseMessage.contains(CONNECTION_RESET_MESSAGE)
			|| lowerCaseMessage.contains(STREAM_CLOSED_MESSAGE);
	}

	private GeneralErrorCode resolveGeneralErrorCode(ResponseStatusException ex) {
		if (ex.getStatusCode().isSameCodeAs(HttpStatus.BAD_REQUEST)) {
			return GeneralErrorCode._BAD_REQUEST;
		}
		if (ex.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
			return GeneralErrorCode._UNAUTHORIZED;
		}
		if (ex.getStatusCode().isSameCodeAs(HttpStatus.FORBIDDEN)) {
			return GeneralErrorCode._FORBIDDEN;
		}
		if (ex.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
			return GeneralErrorCode._NOT_FOUND;
		}
		if (ex.getStatusCode().isSameCodeAs(HttpStatus.METHOD_NOT_ALLOWED)) {
			return GeneralErrorCode._METHOD_NOT_ALLOWED;
		}
		return GeneralErrorCode._INTERNAL_SERVER_ERROR;
	}
}

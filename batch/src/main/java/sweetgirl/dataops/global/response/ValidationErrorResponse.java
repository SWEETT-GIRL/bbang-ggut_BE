package sweetgirl.dataops.global.response;

import java.util.List;

import org.springframework.validation.FieldError;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ValidationErrorResponse {

	private final List<FieldErrorDetail> errors;

	public static ValidationErrorResponse from(List<FieldError> fieldErrors) {
		List<FieldErrorDetail> details = fieldErrors.stream()
			.map(error -> new FieldErrorDetail(error.getField(), error.getDefaultMessage()))
			.toList();
		return new ValidationErrorResponse(details);
	}

	@Getter
	@AllArgsConstructor
	public static class FieldErrorDetail {

		private final String field;
		private final String message;
	}
}

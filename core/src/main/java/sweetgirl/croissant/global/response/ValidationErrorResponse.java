package sweetgirl.croissant.global.response;

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
		List<FieldErrorDetail> errors = fieldErrors.stream()
			.map(FieldErrorDetail::from)
			.toList();
		return new ValidationErrorResponse(errors);
	}

	@Getter
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class FieldErrorDetail {

		private final String field;

		private final String message;

		private final Object rejectedValue;

		public static FieldErrorDetail from(FieldError fieldError) {
			return new FieldErrorDetail(
				fieldError.getField(),
				fieldError.getDefaultMessage(),
				fieldError.getRejectedValue());
		}
	}
}

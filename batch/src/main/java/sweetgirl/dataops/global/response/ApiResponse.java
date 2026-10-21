package sweetgirl.dataops.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.dataops.global.code.BaseCode;
import sweetgirl.dataops.global.code.BaseErrorCode;
import sweetgirl.dataops.global.code.GeneralSuccessCode;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@JsonPropertyOrder({"success", "code", "message", "data"})
public class ApiResponse<T> {

	private boolean success;

	private String code;

	private String message;

	@JsonInclude(JsonInclude.Include.NON_NULL)
	private T data;

	public static <T> ApiResponse<T> ok(T data) {
		return success(GeneralSuccessCode._OK, data);
	}

	public static <T> ApiResponse<T> created(T data) {
		return success(GeneralSuccessCode._CREATED, data);
	}

	public static <T> ApiResponse<T> success(BaseCode code, T data) {
		return new ApiResponse<>(true, code.getCode(), code.getMessage(), data);
	}

	public static <T> ApiResponse<T> fail(BaseErrorCode code) {
		return new ApiResponse<>(false, code.getCode(), code.getMessage(), null);
	}

	public static <T> ApiResponse<T> fail(BaseErrorCode code, String message) {
		return new ApiResponse<>(false, code.getCode(), message, null);
	}

	public static <T> ApiResponse<T> fail(BaseErrorCode code, String message, T data) {
		return new ApiResponse<>(false, code.getCode(), message, data);
	}
}

package sweetgirl.croissant.store.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum StoreReportErrorCode implements BaseErrorCode {

	REPORT_IMAGE_COUNT_EXCEEDED(HttpStatus.BAD_REQUEST, "REPORT4001", "이미지는 최대 5장까지 업로드 가능합니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;

}

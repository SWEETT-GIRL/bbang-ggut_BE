package sweetgirl.croissant.report.code;

import static org.springframework.http.HttpStatus.*;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum UserReportErrorCode implements BaseErrorCode {

	REPORT_TARGET_NOT_FOUND(NOT_FOUND, "REPORT_TARGET_NOT_FOUND", "신고 대상을 찾을 수 없습니다."),
	REPORT_SELF_TARGET_NOT_ALLOWED(
			BAD_REQUEST, "REPORT_SELF_TARGET_NOT_ALLOWED", "본인이 작성한 대상은 신고할 수 없습니다."),
	REPORT_ALREADY_EXISTS(CONFLICT, "REPORT_ALREADY_EXISTS", "이미 신고한 대상입니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}

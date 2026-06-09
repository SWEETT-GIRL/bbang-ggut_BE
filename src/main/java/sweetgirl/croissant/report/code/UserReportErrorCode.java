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
	REPORT_ALREADY_EXISTS(CONFLICT, "REPORT_ALREADY_EXISTS", "이미 신고한 대상입니다."),
	APPEAL_ACTION_NOT_FOUND(NOT_FOUND, "APPEAL_ACTION_NOT_FOUND", "이의제기할 조치를 찾을 수 없습니다."),
	APPEAL_FORBIDDEN(FORBIDDEN, "APPEAL_FORBIDDEN", "본인에게 적용된 조치만 이의제기할 수 있습니다."),
	APPEAL_ACTION_NOT_ALLOWED(BAD_REQUEST, "APPEAL_ACTION_NOT_ALLOWED", "이의제기할 수 없는 조치입니다."),
	APPEAL_PERIOD_EXPIRED(BAD_REQUEST, "APPEAL_PERIOD_EXPIRED", "이의제기 가능 기간이 지났습니다."),
	APPEAL_ALREADY_EXISTS(CONFLICT, "APPEAL_ALREADY_EXISTS", "이미 이의제기한 조치입니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}

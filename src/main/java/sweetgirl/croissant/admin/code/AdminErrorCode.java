package sweetgirl.croissant.admin.code;

import static org.springframework.http.HttpStatus.*;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum AdminErrorCode implements BaseErrorCode {

	REPORT_NOT_FOUND(NOT_FOUND, "ADMIN404_1", "제보를 찾을 수 없습니다."),
	REPORT_ALREADY_REVIEWED(CONFLICT, "ADMIN409_1", "이미 처리된 제보입니다."),
	REPORT_INVALID_ACTION(BAD_REQUEST, "ADMIN400_1", "action은 ACCEPT 또는 REJECT여야 합니다."),
	REPORT_INVALID_KIND(BAD_REQUEST, "ADMIN400_2", "kind는 STORE_REPORT 또는 MENU_ADD_REPORT여야 합니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}

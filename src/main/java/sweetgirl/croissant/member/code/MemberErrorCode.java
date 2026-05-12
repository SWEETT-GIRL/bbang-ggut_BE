package sweetgirl.croissant.member.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum MemberErrorCode implements BaseErrorCode {

	MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "MEMBER404", "존재하지 않는 회원입니다."),
	NOTIFICATION_SETTING_NOT_FOUND(HttpStatus.NOT_FOUND, "MEMBER404_1", "알림 설정을 찾을 수 없습니다."),
	MENU_NOT_FOUND(HttpStatus.NOT_FOUND, "MENU404", "존재하지 않는 메뉴입니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}

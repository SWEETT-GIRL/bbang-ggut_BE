package sweetgirl.croissant.member.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum MemberErrorCode implements BaseErrorCode {

	MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "MEMBER404", "존재하지 않는 회원입니다."),
	MEMBER_DELETED(HttpStatus.GONE, "MEMBER410", "탈퇴한 회원입니다."),
	MENU_NOT_FOUND(HttpStatus.NOT_FOUND, "MENU404", "존재하지 않는 메뉴입니다."),
	NOTIFICATION_RADIUS_ALREADY_MIN(HttpStatus.BAD_REQUEST, "RADIUS400_MIN", "이미 최소 반경입니다."),
	NOTIFICATION_RADIUS_ALREADY_MAX(HttpStatus.BAD_REQUEST, "RADIUS400_MAX", "이미 최대 반경입니다."),
	NOTIFICATION_SETTING_NOT_FOUND(HttpStatus.NOT_FOUND, "MEMBER404_SETTING", "알림 설정이 존재하지 않습니다."),
	MEMBER_ALREADY_DELETED(HttpStatus.BAD_REQUEST, "MEMBER400_DELETED", "이미 탈퇴한 회원입니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}

package sweetgirl.croissant.member.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum MemberErrorCode implements BaseErrorCode {

	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "MEMBER401", "인증이 필요합니다."),
	SOCIAL_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "MEMBER401_SOCIAL_TOKEN_INVALID", "소셜 로그인 토큰이 유효하지 않습니다."),
	MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "MEMBER404", "존재하지 않는 회원입니다."),
	MEMBER_DELETED(HttpStatus.GONE, "MEMBER410", "탈퇴한 회원입니다."),
	MENU_NOT_FOUND(HttpStatus.NOT_FOUND, "MENU404", "존재하지 않는 메뉴입니다."),
	NOTIFICATION_RADIUS_ALREADY_MIN(HttpStatus.BAD_REQUEST, "RADIUS400_MIN", "이미 최소 반경입니다."),
	NOTIFICATION_RADIUS_ALREADY_MAX(HttpStatus.BAD_REQUEST, "RADIUS400_MAX", "이미 최대 반경입니다."),
	MEMBER_ALREADY_DELETED(HttpStatus.BAD_REQUEST, "MEMBER400_DELETED", "이미 탈퇴한 회원입니다."),
	MEMBER_WRITE_RESTRICTED(HttpStatus.FORBIDDEN, "MEMBER403_WRITE_RESTRICTED", "작성 제한 상태입니다."),
	GUEST_LOGIN_REQUIRED(HttpStatus.FORBIDDEN, "MEMBER403_GUEST",
			"로그인하면 지금까지 쌓은 것이 그대로 따라옵니다."),
	MEMBER_BLOCK_SELF_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "MEMBER_BLOCK_SELF_NOT_ALLOWED", "본인은 차단할 수 없습니다."),
	MEMBER_BLOCK_TARGET_NOT_FOUND(HttpStatus.NOT_FOUND, "MEMBER_BLOCK_TARGET_NOT_FOUND", "차단할 회원을 찾을 수 없습니다."),
	MEMBER_BANNED(HttpStatus.FORBIDDEN, "MEMBER403_BANNED", "강퇴된 회원입니다."),
	MEMBER_PROFILE_RESTRICTED(HttpStatus.FORBIDDEN, "MEMBER403_PROFILE_RESTRICTED", "지금은 프로필을 바꿀 수 없습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}

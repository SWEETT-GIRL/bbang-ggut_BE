package sweetgirl.croissant.profile.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum ProfileErrorCode implements BaseErrorCode {

	PROFILE_MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "PROFILE404_MEMBER", "프로필을 찾을 수 없습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}

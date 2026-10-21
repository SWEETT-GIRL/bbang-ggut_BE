package sweetgirl.croissant.follow.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum FollowErrorCode implements BaseErrorCode {

	FOLLOW_SELF(HttpStatus.BAD_REQUEST, "FOLLOW400_SELF", "나 자신은 팔로우할 수 없습니다."),
	FOLLOW_TARGET_NOT_FOUND(HttpStatus.NOT_FOUND, "FOLLOW404_TARGET", "팔로우할 회원을 찾을 수 없습니다."),
	FOLLOW_BLOCKED(HttpStatus.FORBIDDEN, "FOLLOW403_BLOCKED", "차단 관계에서는 팔로우할 수 없습니다."),
	FOLLOW_DAILY_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "FOLLOW429_DAILY_LIMIT", "오늘은 더 팔로우할 수 없습니다. 내일 다시 해 주세요.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}

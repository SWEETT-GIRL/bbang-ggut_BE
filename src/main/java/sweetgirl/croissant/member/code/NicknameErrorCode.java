package sweetgirl.croissant.member.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum NicknameErrorCode implements BaseErrorCode {

	NICKNAME_EXHAUSTED(HttpStatus.INTERNAL_SERVER_ERROR, "NICKNAME500_EXHAUSTED", "사용 가능한 닉네임이 없습니다."),
	NICKNAME_FILE_LOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "NICKNAME500_FILE", "닉네임 단어 파일 로드에 실패했습니다."),
	NICKNAME_DRAW_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "NICKNAME429_DRAW_LIMIT", "닉네임은 하루에 한 번만 뽑을 수 있습니다."),
	NICKNAME_NO_PENDING(HttpStatus.BAD_REQUEST, "NICKNAME400_NO_PENDING", "적용할 닉네임이 없습니다."),
	NICKNAME_TAKEN(HttpStatus.CONFLICT, "NICKNAME409_TAKEN", "이미 다른 사람이 쓰고 있는 닉네임입니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}

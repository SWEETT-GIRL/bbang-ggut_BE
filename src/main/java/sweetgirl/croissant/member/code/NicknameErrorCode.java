package sweetgirl.croissant.member.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum NicknameErrorCode implements BaseErrorCode {

	NICKNAME_EXHAUSTED(HttpStatus.INTERNAL_SERVER_ERROR, "NICKNAME500_EXHAUSTED", "사용 가능한 닉네임이 없습니다."),
	NICKNAME_FILE_LOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "NICKNAME500_FILE", "닉네임 단어 파일 로드에 실패했습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}

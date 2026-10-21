package sweetgirl.croissant.appversion.code;

import static org.springframework.http.HttpStatus.*;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum AppVersionErrorCode implements BaseErrorCode {

	INVALID_VERSION_FORMAT(BAD_REQUEST, "APPVERSION400_1", "버전은 1.2.3 형식이어야 합니다."),
	MIN_ABOVE_LATEST(BAD_REQUEST, "APPVERSION400_2", "최소 지원 버전이 최신 버전보다 높을 수 없습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}

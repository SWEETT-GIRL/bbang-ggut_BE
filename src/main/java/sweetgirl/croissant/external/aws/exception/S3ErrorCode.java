package sweetgirl.croissant.external.aws.exception;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum S3ErrorCode implements BaseErrorCode {

	INVALID_FILE_TYPE(HttpStatus.BAD_REQUEST, "S34001", "지원하지 않는 파일 형식입니다."),
	PRE_SIGNED_URL_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "S35001", "URL 생성 중 오류가 발생했습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}

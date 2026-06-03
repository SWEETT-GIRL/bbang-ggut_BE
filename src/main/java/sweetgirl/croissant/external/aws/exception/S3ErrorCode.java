package sweetgirl.croissant.external.aws.exception;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum S3ErrorCode implements BaseErrorCode {

	INVALID_FILE_TYPE(HttpStatus.BAD_REQUEST, "S34001", "지원하지 않는 파일 형식입니다."),
	IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "S34002", "이미지를 찾을 수 없습니다."),
	PRE_SIGNED_URL_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "S35001", "URL 생성 중 오류가 발생했습니다."),
	DELETE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "S35002", "이미지 삭제 중 오류가 발생했습니다."),
	COPY_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "S35003", "이미지 복사 중 오류가 발생했습니다."),
	UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "S35004", "이미지 업로드 중 오류가 발생했습니다."),
	LIST_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "S35005", "S3 객체 목록 조회 중 오류가 발생했습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}

package sweetgirl.croissant.breadlog.code;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum BreadLogErrorCode implements BaseErrorCode {

	BREAD_RECORD_NOT_FOUND(NOT_FOUND, "BREAD_RECORD_NOT_FOUND", "빵 기록을 찾을 수 없습니다."),
	BREAD_RECORD_ACCESS_DENIED(FORBIDDEN, "BREAD_RECORD_ACCESS_DENIED", "빵 기록을 수정하거나 삭제할 권한이 없습니다."),
	DUPLICATE_PHOTO_ORDER(BAD_REQUEST, "BREAD_RECORD_DUPLICATE_PHOTO_ORDER", "사진 순서는 중복될 수 없습니다."),
	TAG_PHOTO_NOT_FOUND(BAD_REQUEST, "BREAD_RECORD_TAG_PHOTO_NOT_FOUND", "태그가 가리키는 사진을 찾을 수 없습니다."),
	INVALID_TAG_POSITION(BAD_REQUEST, "BREAD_RECORD_INVALID_TAG_POSITION", "태그 좌표는 0부터 1 사이여야 합니다."),
	INVALID_LOVED_BREAD(BAD_REQUEST, "BREAD_RECORD_INVALID_LOVED_BREAD", "가장 맛있었던 빵은 사진 태그 중에서 선택해 주세요."),
	INVALID_IMAGE_MIME_TYPE(BAD_REQUEST, "BREAD_RECORD_INVALID_IMAGE_MIME_TYPE", "지원하지 않는 빵 기록 이미지 형식입니다."),
	INVALID_IMAGE_KEY(BAD_REQUEST, "BREAD_RECORD_INVALID_IMAGE_KEY", "유효하지 않은 빵 기록 이미지 키입니다."),
	INVALID_DATE_RANGE(BAD_REQUEST, "BREAD_RECORD_INVALID_DATE_RANGE", "조회 시작 시각은 종료 시각보다 빨라야 합니다."),
	INVALID_PAGE_REQUEST(BAD_REQUEST, "BREAD_RECORD_INVALID_PAGE_REQUEST", "페이지는 0 이상, 페이지 크기는 1부터 100 사이여야 합니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}

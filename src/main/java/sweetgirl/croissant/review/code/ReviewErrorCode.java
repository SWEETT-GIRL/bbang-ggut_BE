package sweetgirl.croissant.review.code;

import static org.springframework.http.HttpStatus.*;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum ReviewErrorCode implements BaseErrorCode {

	INVALID_REVIEW_SORT(BAD_REQUEST, "REVIEW_INVALID_SORT", "지원하지 않는 리뷰 정렬 방식입니다."),
	INVALID_RATING(
			BAD_REQUEST, "REVIEW_INVALID_RATING", "별점은 0.5부터 5.0까지 0.5 단위로 입력해 주세요."),
	INVALID_TEXTURE_TAGS(
			BAD_REQUEST, "REVIEW_INVALID_TEXTURE_TAGS", "식감 태그는 1개 이상 5개 이하로 입력해 주세요."),
	REVIEW_IMAGE_COUNT_EXCEEDED(
			BAD_REQUEST, "REVIEW_IMAGE_COUNT_EXCEEDED", "리뷰 이미지는 최대 5장까지 업로드 가능합니다."),
	INVALID_REVIEW_IMAGE_MIME_TYPE(
			BAD_REQUEST,
			"REVIEW_INVALID_IMAGE_MIME_TYPE",
			"리뷰 이미지는 JPEG 또는 PNG만 업로드할 수 있습니다."),
	INVALID_REVIEW_IMAGE_KEY(
			BAD_REQUEST, "REVIEW_INVALID_IMAGE_KEY", "유효하지 않은 리뷰 이미지 키입니다."),
	REVIEW_CONTENT_TOO_LONG(
			BAD_REQUEST, "REVIEW_CONTENT_TOO_LONG", "리뷰 내용은 최대 1000자까지 입력할 수 있습니다."),
	REVIEW_CONTENT_REJECTED(
			BAD_REQUEST,
			"REVIEW_CONTENT_REJECTED",
			"부적절하거나 개인정보로 보이는 표현이 포함되어 있어 등록할 수 없습니다."),
	REVIEW_EDIT_PERIOD_EXPIRED(
			FORBIDDEN,
			"REVIEW_EDIT_PERIOD_EXPIRED",
			"리뷰는 작성 후 24시간 이내에만 수정할 수 있습니다."),
	REVIEW_NOT_FOUND(NOT_FOUND, "REVIEW_NOT_FOUND", "존재하지 않는 리뷰입니다."),
	REVIEW_ACCESS_DENIED(FORBIDDEN, "REVIEW_ACCESS_DENIED", "리뷰를 수정하거나 삭제할 권한이 없습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}

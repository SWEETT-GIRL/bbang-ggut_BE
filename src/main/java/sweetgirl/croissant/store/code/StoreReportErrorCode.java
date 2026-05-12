package sweetgirl.croissant.store.code;

import static org.springframework.http.HttpStatus.*;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum StoreReportErrorCode implements BaseErrorCode {

	REPORT_IMAGE_COUNT_EXCEEDED(BAD_REQUEST, "REPORT4001", "이미지는 최대 5장까지 업로드 가능합니다."),
	REPORT_CONTENT_EMPTY(BAD_REQUEST, "REPORT4002", "삭제 또는 추가 이미지를 하나 이상 입력해 주세요."),
	REPORT_IMAGE_NOT_OWNED(BAD_REQUEST, "REPORT4003", "삭제 요청한 이미지가 해당 가게 소유가 아닙니다."),
	REPORT_INVALID_AWS_KEY(BAD_REQUEST, "REPORT4004", "유효하지 않은 이미지 키입니다."),
	REPORT_TIME_REQUIRED(BAD_REQUEST, "REPORT4005", "영업 중인 요일은 오픈·마감 시간이 필요합니다."),
	REPORT_INVALID_TIME_RANGE(BAD_REQUEST, "REPORT4006", "오픈 시간은 마감 시간보다 빨라야 합니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;

}

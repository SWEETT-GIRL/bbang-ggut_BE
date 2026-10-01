package sweetgirl.croissant.monthlyreport.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum MonthlyReportErrorCode implements BaseErrorCode {

	INVALID_MONTH(HttpStatus.BAD_REQUEST, "MONTHLY_REPORT4001", "월은 2000-01부터 2099-12까지 YYYY-MM 형식이어야 합니다."),
	INVALID_PAGE(HttpStatus.BAD_REQUEST, "MONTHLY_REPORT4002", "page는 0 이상, size는 1부터 100까지여야 합니다."),
	NOT_PUBLISHED(HttpStatus.NOT_FOUND, "MONTHLY_REPORT4041", "아직 발행되지 않은 월간 빵일지입니다."),
	INVALID_SNAPSHOT(HttpStatus.INTERNAL_SERVER_ERROR, "MONTHLY_REPORT5001", "월간 빵일지 데이터가 올바르지 않습니다."),
	GENERATION_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "MONTHLY_REPORT5031", "월간 빵일지 집계를 완료하지 못했습니다."),
	SOURCE_HISTORY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "MONTHLY_REPORT5032", "월말 기준 활동 이력을 복원할 수 없습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}

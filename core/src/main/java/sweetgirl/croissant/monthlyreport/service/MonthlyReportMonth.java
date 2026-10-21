package sweetgirl.croissant.monthlyreport.service;

import java.time.YearMonth;

import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;

public final class MonthlyReportMonth {

	private MonthlyReportMonth() {
		// 정적 월 형식 검증만 제공한다.
	}

	public static YearMonth parse(String yearMonth) {
		if (yearMonth == null || !yearMonth.matches("20[0-9]{2}-(0[1-9]|1[0-2])")) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_MONTH);
		}
		return YearMonth.parse(yearMonth);
	}
}

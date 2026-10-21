package sweetgirl.croissant.monthlyreport.enums;

import java.time.YearMonth;
import java.util.Locale;

/** 앱의 기존 표정 순서를 유지한다. 활동 점수와 무관한 월별 장식이다. */
public enum MonthlyReportFaceExpression {
	SMILE, WINK, NEUTRAL, CLOSED;

	public String variant() {
		return name().toLowerCase(Locale.ROOT);
	}

	public static MonthlyReportFaceExpression select(String characterCode, YearMonth month) {
		long offset = Integer.toUnsignedLong(characterCode.hashCode());
		int index = (int)((offset + month.getYear() * 12L + month.getMonthValue() - 1) % values().length);
		return values()[index];
	}
}

package sweetgirl.croissant.monthlyreport.dto;

import java.time.Instant;

/** 발행된 월 목록. link는 푸시와 같은 앱 내부 경로다. */
public record MonthlyReportListItem(
		String yearMonth, Instant publishedAt, String link, MonthlyReportResponse.Character character,
		String coverImageKey) {

	public static MonthlyReportListItem of(
			String yearMonth, Instant publishedAt, MonthlyReportResponse.Character character, String coverImageKey) {
		return new MonthlyReportListItem(yearMonth, publishedAt, "/reports/monthly/" + yearMonth, character,
			coverImageKey);
	}
}

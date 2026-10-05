package sweetgirl.croissant.monthlyreport.admin.dto;

import java.util.Map;

import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportDraft;

public record MonthlyReportProgressResponse(MonthlyReportRunResponse run, Map<MonthlyReportDraft.State, Long> counts) {
	public MonthlyReportProgressResponse {
		counts = Map.copyOf(counts);
	}
}

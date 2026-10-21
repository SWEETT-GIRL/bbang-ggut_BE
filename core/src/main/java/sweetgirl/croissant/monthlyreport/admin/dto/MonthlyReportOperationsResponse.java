package sweetgirl.croissant.monthlyreport.admin.dto;

import java.util.Map;

import sweetgirl.croissant.global.response.SliceResponse;

public record MonthlyReportOperationsResponse(long published, long viewed, long notificationOpened,
		Map<String, Long> pushCounts, SliceResponse<MonthlyReportMemberStatusResponse> members) {
	public MonthlyReportOperationsResponse {
		pushCounts = Map.copyOf(pushCounts);
	}
}

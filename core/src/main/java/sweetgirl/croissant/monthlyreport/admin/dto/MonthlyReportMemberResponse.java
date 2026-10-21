package sweetgirl.croissant.monthlyreport.admin.dto;

import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportDraft;

public record MonthlyReportMemberResponse(Long memberId, MonthlyReportDraft.State state, int attempts,
		String errorCode) {

}

package sweetgirl.croissant.monthlyreport.admin.dto;

import java.time.Instant;
import java.util.UUID;

import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportRun;

public record MonthlyReportRunResponse(UUID id, String yearMonth, String assetVersion, Long targetMemberId,
		MonthlyReportRun.State state, long afterMemberId, boolean scanComplete, boolean sourcesReady,
		Instant pushAt, Instant createdAt, String errorCode) {
	public static MonthlyReportRunResponse from(MonthlyReportRun run) {
		return new MonthlyReportRunResponse(run.getId(), run.getYearMonth(), run.getAssetVersion(),
			run.getTargetMemberId(),
			run.getState(), run.getAfterMemberId(), run.isScanComplete(), run.isSourcesReady(), run.getPushAt(),
			run.getCreatedAt(), run.getErrorCode());
	}
}

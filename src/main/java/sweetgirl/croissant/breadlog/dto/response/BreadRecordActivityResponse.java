package sweetgirl.croissant.breadlog.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record BreadRecordActivityResponse(
		LocalDate asOfDate,
		String timeZone,
		OffsetDateTime computedAt,
		boolean recordedToday,
		int currentStreakDays,
		int nextStreakDays,
		long activeRecordCount,
		long normalRecordCount,
		LocalDate lastRecordedDate,
		Long latestNormalRecordId,
		LocalDate latestNormalEatenDate,
		String policyVersion) {

	public static final String POLICY_VERSION = "CREATED_KST_INCLUDE_TUTORIAL_V1";
}

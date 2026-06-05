package sweetgirl.croissant.report.enums;

public enum ModerationTargetType {
	REVIEW,
	MEMBER_PROFILE,
	MEMBER_RESTRICTION;

	public static ModerationTargetType fromReportTargetType(ReportTargetType targetType) {
		return switch (targetType) {
			case REVIEW -> REVIEW;
			case MEMBER_PROFILE -> MEMBER_PROFILE;
		};
	}
}

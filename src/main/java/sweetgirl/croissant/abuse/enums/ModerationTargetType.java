package sweetgirl.croissant.abuse.enums;

public enum ModerationTargetType {
	REVIEW,
	MEMBER_PROFILE,
	MEMBER_RESTRICTION,
	COURSE,
	RECORD,
	COMMENT;

	public static ModerationTargetType fromAbuseReportTargetType(AbuseReportTargetType targetType) {
		return switch (targetType) {
			case REVIEW -> REVIEW;
			case MEMBER_PROFILE -> MEMBER_PROFILE;
			case COURSE -> COURSE;
			case RECORD -> RECORD;
			case COMMENT -> COMMENT;
		};
	}
}

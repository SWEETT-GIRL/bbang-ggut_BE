package sweetgirl.croissant.badge.service;

public record MemberConditionSnapshot(
		long savedStoreCount,
		long courseCount,
		long recordCount,
		long tourFinishCount,
		long maxStoreVisitCount) {
}

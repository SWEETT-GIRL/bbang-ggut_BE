package sweetgirl.croissant.badge.enums;

public enum ConditionType {
	STARTER_ALL,
	SAVED_STORE_COUNT,
	COURSE_COUNT,
	RECORD_COUNT,
	TOUR_FINISH_COUNT,
	STORE_REVISIT;

	public int target(Integer threshold) {
		if (this == STARTER_ALL) {
			if (threshold != null) {
				throw new IllegalArgumentException("STARTER_ALL에는 threshold를 지정할 수 없습니다.");
			}
			return 3;
		}
		if (threshold == null || threshold < 1) {
			throw new IllegalArgumentException("threshold는 1 이상이어야 합니다.");
		}
		return threshold;
	}
}

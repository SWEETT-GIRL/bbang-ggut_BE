package sweetgirl.croissant.badge.service;

public record ConditionProgress(
		long current,
		int target) {

	public boolean done() {
		return current >= target;
	}
}

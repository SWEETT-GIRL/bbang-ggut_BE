package sweetgirl.croissant.searchmiss.service;

/**
 * 판정 도중 실패해 건너뛴 행. 판정이 안 찍혀 다음 날 다시 집힌다.
 */
public record SearchMissTriageFailure(
		String keyword,
		String reason) {
}

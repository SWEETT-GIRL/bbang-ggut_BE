package sweetgirl.croissant.retention.policy;

/**
 * 확인된 사실만 전달한다. 최초 이용일이나 기록 이력을 모르면 추정값으로 채우지 않는다.
 * rankingEligible은 NORMAL 기록의 ACTIVE 가게로 실제 순위를 시작할 수 있을 때만 참이다.
 */
public record RetentionPolicyContext(
		int daySinceFirstSeen,
		boolean guest,
		boolean hasEverRecorded,
		boolean rankingEligible,
		boolean rankingAlreadyCreated,
		boolean rankingMessageSent,
		boolean calendarMessageSent,
		boolean recordedAfterD1Message) {
}

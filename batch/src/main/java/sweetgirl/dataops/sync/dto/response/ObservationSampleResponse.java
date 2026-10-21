package sweetgirl.dataops.sync.dto.response;

/** 관찰 표본 선정 결과 — scheduledNow 곳만 due, 나머지는 next_due_at NULL 로 보류 */
public record ObservationSampleResponse(
		int sampleSize,
		int menuZeroCount,
		int withMenuCount,
		int scheduledNow,
		int onHold) {
}

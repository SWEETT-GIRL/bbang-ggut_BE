package sweetgirl.dataops.autoapply;

import java.util.List;

/** Python 워커가 사용하는 기존 HTTP 응답 필드를 유지한다. */
public record AutomaticApplyResponse(String captureId, int applied, int alreadyApplied, int held, int failed,
		int unknown, String observationHold, List<ItemResponse> items) {
	public static AutomaticApplyResponse from(AutomaticApplyResponseDto result) {
		return new AutomaticApplyResponse(result.captureId(), result.applied(), result.alreadyApplied(), result.held(),
			result.failed(), result.unknown(), result.observationHold(), result.items().stream().map(ItemResponse::from).toList());
	}

	public record ItemResponse(long proposalId, String status, String reason) {
		public static ItemResponse from(AutomaticApplyItemResponseDto result) {
			return new ItemResponse(result.proposalId(), result.status(), result.reason());
		}
	}
}

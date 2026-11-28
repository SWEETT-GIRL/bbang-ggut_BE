package sweetgirl.dataops.autoapply;

import java.util.List;

public record AutomaticApplyResponseDto(String captureId, int applied, int alreadyApplied, int held, int failed,
		int unknown, String observationHold, List<AutomaticApplyItemResponseDto> items) {
	public static AutomaticApplyResponseDto from(String captureId, String observationHold, List<AutomaticApplyItemResponseDto> items) {
		return new AutomaticApplyResponseDto(captureId, count(items, "APPLIED"), count(items, "ALREADY_APPLIED"),
			count(items, "HELD"), count(items, "FAILED"), count(items, "UNKNOWN"), observationHold, List.copyOf(items));
	}

	private static int count(List<AutomaticApplyItemResponseDto> items, String status) {
		return (int)items.stream().filter(outcome -> status.equals(outcome.status())).count();
	}
}

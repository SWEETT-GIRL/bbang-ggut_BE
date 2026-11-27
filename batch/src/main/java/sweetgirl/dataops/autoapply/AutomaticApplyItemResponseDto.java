package sweetgirl.dataops.autoapply;

import java.util.Set;

/** 저장된 결과의 필드와 사유 코드는 재시도에서도 그대로 유지한다. */
public record AutomaticApplyItemResponseDto(long proposalId, String status, String reason) {
	private static final Set<String> CONFLICTS = Set.of("VALUE_DRIFTED", "MENU_CATEGORY_CHANGED", "MENU_CONFLICT");

	public static AutomaticApplyItemResponseDto from(AutomaticApplyQueryService.ProposalStateResponseDto proposal) {
		String status = switch (proposal.status()) {
			case APPROVED -> "ALREADY_APPLIED";
			case APPLYING, UNKNOWN -> "UNKNOWN";
			case FAILED -> CONFLICTS.contains(proposal.reason()) ? "HELD" : "FAILED";
			default -> "HELD";
		};
		return new AutomaticApplyItemResponseDto(proposal.id(), status, proposal.reason());
	}

	public AutomaticApplyItemResponseDto replayed() {
		return "APPLIED".equals(status) ? new AutomaticApplyItemResponseDto(proposalId, "ALREADY_APPLIED", "REPLAY") : this;
	}
}

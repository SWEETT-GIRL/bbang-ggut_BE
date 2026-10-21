package sweetgirl.dataops.serving.dto.response;

import java.time.LocalDateTime;

import sweetgirl.dataops.serving.entity.ApplyLog;
import sweetgirl.dataops.serving.enums.ApplyOrigin;

public record ApplyLogResponse(
		Long applyLogId,
		Long storeId,
		String target,
		String oldValue,
		String newValue,
		ApplyOrigin origin,
		Long proposalId,
		Long snapshotId,
		LocalDateTime appliedAt) {

	public static ApplyLogResponse from(ApplyLog applyLog) {
		return new ApplyLogResponse(
			applyLog.getId(),
			applyLog.getStoreId(),
			applyLog.getTarget(),
			applyLog.getOldValue(),
			applyLog.getNewValue(),
			applyLog.getOrigin(),
			applyLog.getProposalId(),
			applyLog.getSnapshotId(),
			applyLog.getAppliedAt());
	}
}

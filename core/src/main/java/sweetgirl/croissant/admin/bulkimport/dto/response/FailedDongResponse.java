package sweetgirl.croissant.admin.bulkimport.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.admin.bulkimport.entity.BulkImportProgress;

public record FailedDongResponse(
		String region1st,
		String region2nd,
		String region3rd,
		String errorMessage,
		LocalDateTime lastAttemptedAt) {

	public static FailedDongResponse from(BulkImportProgress progress) {
		return new FailedDongResponse(
			progress.getRegion1st(), progress.getRegion2nd(), progress.getRegion3rd(),
			progress.getErrorMessage(), progress.getLastAttemptedAt());
	}
}

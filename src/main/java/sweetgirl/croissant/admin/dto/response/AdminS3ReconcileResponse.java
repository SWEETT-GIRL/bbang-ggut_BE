package sweetgirl.croissant.admin.dto.response;

import java.util.List;

public record AdminS3ReconcileResponse(
		boolean dryRun,
		int s3KeyCount,
		int dbKeyCount,
		int orphanCount,
		int deletedCount,
		List<String> orphanKeys,
		int missingCount,
		int failedMarkedCount,
		List<String> missingKeys) {
}

package sweetgirl.croissant.admin.bulkimport.dto.response;

public record BulkImportStartResponse(
		long totalDongs,
		long pendingDongs,
		long completedDongs) {
}

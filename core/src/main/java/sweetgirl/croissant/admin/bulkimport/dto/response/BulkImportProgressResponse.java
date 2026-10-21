package sweetgirl.croissant.admin.bulkimport.dto.response;

public record BulkImportProgressResponse(
		boolean running,
		long totalDongs,
		long completedDongs,
		long failedDongs,
		long inProgressDongs,
		long pendingDongs,
		long totalInserted) {
}

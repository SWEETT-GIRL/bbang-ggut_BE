package sweetgirl.croissant.external.aws.dto;

import java.util.List;

public record ImageUploadFromUrlResponse(
		List<Item> results,
		int successCount,
		int failureCount) {

	public enum Status {
		SUCCESS,
		SKIPPED,
		INVALID_URL,
		DOWNLOAD_FAILED,
		UPLOAD_FAILED,
		DB_FAILED
	}

	public record Item(
			String url,
			Status status,
			Long imageId,
			String awsKey,
			String reason) {

		public static Item success(String url, Long imageId, String awsKey) {
			return new Item(url, Status.SUCCESS, imageId, awsKey, null);
		}

		public static Item skipped(String url, Long imageId, String awsKey) {
			return new Item(url, Status.SKIPPED, imageId, awsKey, "duplicate source_url");
		}

		public static Item failure(String url, Status status, String reason) {
			return new Item(url, status, null, null, reason);
		}
	}
}

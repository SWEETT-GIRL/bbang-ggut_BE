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
		/** 점주가 등록한 사진 도메인이 아니다 — 방문자 리뷰·블로그·프로필·클립 썸네일 */
		REJECTED_SOURCE,
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

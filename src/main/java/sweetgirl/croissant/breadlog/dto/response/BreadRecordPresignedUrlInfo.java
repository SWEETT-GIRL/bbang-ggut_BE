package sweetgirl.croissant.breadlog.dto.response;

public record BreadRecordPresignedUrlInfo(
		String fileName,
		String contentType,
		String uploadUrl,
		String awsKey) {
}

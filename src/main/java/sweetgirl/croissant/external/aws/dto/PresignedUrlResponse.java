package sweetgirl.croissant.external.aws.dto;

public record PresignedUrlResponse(
		String fileName,
		String contentType,
		String uploadUrl,
		String awsKey) {

}

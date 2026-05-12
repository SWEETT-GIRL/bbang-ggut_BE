package sweetgirl.croissant.external.aws.dto;

public record PresignedUrlResponse(
		String url,
		String awsKey) {

}

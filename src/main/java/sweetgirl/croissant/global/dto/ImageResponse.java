package sweetgirl.croissant.global.dto;

public record ImageResponse(
		Long id,
		String awsKey,
		String resizedKey320,
		String resizedKey640,
		String resizedKey1024) {
}

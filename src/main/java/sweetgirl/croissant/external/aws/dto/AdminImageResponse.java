package sweetgirl.croissant.external.aws.dto;

import java.time.LocalDateTime;

public record AdminImageResponse(
		Long id,
		String awsKey,
		String mimeType,
		int displayOrder,
		LocalDateTime createdAt,
		String resizedKey320,
		String resizedKey640,
		String resizedKey1024) {

}

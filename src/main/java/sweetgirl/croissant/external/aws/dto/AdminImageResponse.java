package sweetgirl.croissant.external.aws.dto;

import java.time.LocalDateTime;

public record AdminImageResponse(
		Long id,
		String awsKey,
		String mimeType,
		LocalDateTime createdAt) {

}

package sweetgirl.croissant.review.dto.request;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewPresignedUrlRequest(
		@NotNull @Size(min = 1, max = 5) List<String> mimeTypes) {
}

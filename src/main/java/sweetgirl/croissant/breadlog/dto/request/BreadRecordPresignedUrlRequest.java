package sweetgirl.croissant.breadlog.dto.request;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record BreadRecordPresignedUrlRequest(
		@NotNull @Size(min = 1, max = 10) List<String> mimeTypes) {
}

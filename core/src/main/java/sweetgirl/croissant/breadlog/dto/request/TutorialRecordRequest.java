package sweetgirl.croissant.breadlog.dto.request;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TutorialRecordRequest(
		@NotBlank @Size(max = 60) String templateCode,
		@NotNull LocalDateTime eatenAt) {
}

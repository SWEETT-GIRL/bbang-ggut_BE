package sweetgirl.croissant.moderation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import sweetgirl.croissant.moderation.entity.ForbiddenWord;
import sweetgirl.croissant.moderation.enums.ForbiddenWordKind;

public record ForbiddenWordCreateRequest(
		@NotBlank @Size(max = ForbiddenWord.WORD_MAX_LENGTH) String word,
		@NotNull ForbiddenWordKind kind) {
}

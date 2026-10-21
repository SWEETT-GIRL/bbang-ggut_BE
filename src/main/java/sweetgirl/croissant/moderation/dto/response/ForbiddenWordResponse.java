package sweetgirl.croissant.moderation.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.moderation.entity.ForbiddenWord;
import sweetgirl.croissant.moderation.enums.ForbiddenWordKind;

public record ForbiddenWordResponse(Long id, String word, ForbiddenWordKind kind, LocalDateTime createdAt) {

	public static ForbiddenWordResponse from(ForbiddenWord forbiddenWord) {
		return new ForbiddenWordResponse(forbiddenWord.getId(), forbiddenWord.getWord(), forbiddenWord.getKind(),
			forbiddenWord.getCreatedAt());
	}
}

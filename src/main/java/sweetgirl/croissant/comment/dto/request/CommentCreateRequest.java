package sweetgirl.croissant.comment.dto.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

public record CommentCreateRequest(@NotBlank String content, @Valid List<MentionRequest> mentions) {

	public List<MentionRequest> mentionsOrEmpty() {
		return mentions == null ? List.of() : mentions;
	}
}

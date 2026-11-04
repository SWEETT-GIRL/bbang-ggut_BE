package sweetgirl.croissant.comment.dto.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/** {@code parentCommentId} 를 주면 그 댓글의 답글이다(2026-11-04). 없으면 최상위 댓글. */
public record CommentCreateRequest(@NotBlank String content, @Valid List<MentionRequest> mentions,
		Long parentCommentId) {

	public CommentCreateRequest(String content, List<MentionRequest> mentions) {
		this(content, mentions, null);
	}

	public List<MentionRequest> mentionsOrEmpty() {
		return mentions == null ? List.of() : mentions;
	}
}

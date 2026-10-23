package sweetgirl.croissant.comment.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import sweetgirl.croissant.comment.entity.BreadRecordComment;
import sweetgirl.croissant.comment.entity.CommentMention;
import sweetgirl.croissant.profile.dto.response.CharacterFace;

/**
 * 댓글 한 줄. 탈퇴한 사람의 댓글은 남고 작성자가 비어 온다(memberId · nickname · 얼굴 null → "알 수 없는 사용자").
 * {@code isAuthor} 는 빵로그 쓴 사람의 댓글([작성자] 뱃지), {@code isMine} 은 보는 사람 것.
 */
public record CommentResponse(
		Long id,
		Long memberId,
		String nickname,
		String characterCode,
		String characterImageKey,
		String content,
		List<MentionResponse> mentions,
		LocalDateTime createdAt,
		boolean isAuthor,
		boolean isMine,
		String hiddenStatus) {

	public static final String UNDER_REVIEW = "UNDER_REVIEW";

	public record MentionResponse(Long memberId, int start, int length) {

		public static MentionResponse from(CommentMention mention) {
			return new MentionResponse(mention.getId().getMemberId(), mention.getStartIndex(), mention.getLength());
		}
	}

	public static CommentResponse of(BreadRecordComment comment, List<CommentMention> mentions, CharacterFace face,
			Long recordAuthorId, Long viewerId) {
		boolean withdrawn = comment.getMember().isDeleted();
		Long memberId = comment.getMember().getId();
		return new CommentResponse(
			comment.getId(),
			withdrawn ? null : memberId,
			withdrawn ? null : comment.getMember().getNickname(),
			withdrawn ? null : face.characterCode(),
			withdrawn ? null : face.characterImageKey(),
			comment.getContent(),
			mentions.stream().map(MentionResponse::from).toList(),
			comment.getCreatedAt(),
			memberId.equals(recordAuthorId),
			memberId.equals(viewerId),
			comment.isHidden() ? UNDER_REVIEW : null);
	}
}

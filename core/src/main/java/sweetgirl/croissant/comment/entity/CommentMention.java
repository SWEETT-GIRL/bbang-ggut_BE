package sweetgirl.croissant.comment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.member.entity.Member;

/** 댓글 안 멘션 한 사람. 오프셋은 작성 시 본문 기준이고 본문은 안 바뀐다. 한 댓글에 같은 사람은 한 번. */
@Entity
@Table(name = "comment_mention")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommentMention extends BaseTimeEntity {

	@EmbeddedId
	private CommentMentionId id;

	@MapsId("commentId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "comment_id", nullable = false)
	private BreadRecordComment comment;

	@MapsId("memberId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@Column(nullable = false)
	private int startIndex;

	@Column(nullable = false)
	private int length;

	private CommentMention(BreadRecordComment comment, Member member, int startIndex, int length) {
		this.id = new CommentMentionId(comment.getId(), member.getId());
		this.comment = comment;
		this.member = member;
		this.startIndex = startIndex;
		this.length = length;
	}

	public static CommentMention of(BreadRecordComment comment, Member member, int startIndex, int length) {
		return new CommentMention(comment, member, startIndex, length);
	}
}

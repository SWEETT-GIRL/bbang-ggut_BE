package sweetgirl.croissant.comment.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.comment.entity.CommentMention;
import sweetgirl.croissant.comment.entity.CommentMentionId;

public interface CommentMentionRepository extends JpaRepository<CommentMention, CommentMentionId> {

	List<CommentMention> findAllByIdCommentIdIn(Collection<Long> commentIds);

	/** 한 사람이 언급된 멘션을 전부 지운다 — 탈퇴 때. 댓글 본문은 그대로 남는다. */
	@Modifying
	@Query("DELETE FROM CommentMention m WHERE m.id.memberId = :memberId")
	int deleteAllByMemberId(@Param("memberId") Long memberId);
}

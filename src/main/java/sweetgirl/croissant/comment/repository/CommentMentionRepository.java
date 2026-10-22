package sweetgirl.croissant.comment.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.comment.entity.CommentMention;
import sweetgirl.croissant.comment.entity.CommentMentionId;

public interface CommentMentionRepository extends JpaRepository<CommentMention, CommentMentionId> {

	List<CommentMention> findAllByIdCommentIdIn(Collection<Long> commentIds);
}

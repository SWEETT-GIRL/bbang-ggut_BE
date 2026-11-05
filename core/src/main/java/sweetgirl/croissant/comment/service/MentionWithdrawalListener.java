package sweetgirl.croissant.comment.service;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.comment.repository.CommentMentionRepository;
import sweetgirl.croissant.member.event.MemberWithdrawnEvent;

/** 탈퇴하면 그 사람이 언급된 멘션을 지운다. 댓글 본문은 남고 작성자만 가려진다(CommentResponse). */
@Component
@RequiredArgsConstructor
public class MentionWithdrawalListener {

	private final CommentMentionRepository commentMentionRepository;

	@EventListener
	public void onWithdrawn(MemberWithdrawnEvent event) {
		commentMentionRepository.deleteAllByMemberId(event.memberId());
	}
}

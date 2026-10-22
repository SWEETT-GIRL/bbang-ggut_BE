package sweetgirl.croissant.comment.service;

import static org.mockito.BDDMockito.then;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.comment.repository.CommentMentionRepository;
import sweetgirl.croissant.member.event.MemberWithdrawnEvent;

@ExtendWith(MockitoExtension.class)
@DisplayName("MentionWithdrawalListener")
class MentionWithdrawalListenerTest {

	@Mock
	private CommentMentionRepository commentMentionRepository;

	@InjectMocks
	private MentionWithdrawalListener listener;

	@Test
	@DisplayName("탈퇴 이벤트를 받으면 그 사람의 행을 전부 지운다")
	void deletesAllOfMember() {
		listener.onWithdrawn(new MemberWithdrawnEvent(1L));

		then(commentMentionRepository).should().deleteAllByMemberId(1L);
	}
}

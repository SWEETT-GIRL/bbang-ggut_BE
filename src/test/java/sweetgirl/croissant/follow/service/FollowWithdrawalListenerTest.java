package sweetgirl.croissant.follow.service;

import static org.mockito.BDDMockito.then;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.follow.repository.MemberFollowRequestRepository;
import sweetgirl.croissant.member.event.MemberWithdrawnEvent;

@ExtendWith(MockitoExtension.class)
@DisplayName("FollowWithdrawalListener")
class FollowWithdrawalListenerTest {

	@Mock
	private MemberFollowRepository memberFollowRepository;
	@Mock
	private MemberFollowRequestRepository memberFollowRequestRepository;

	@InjectMocks
	private FollowWithdrawalListener listener;

	@Test
	@DisplayName("탈퇴 이벤트를 받으면 그 사람의 행을 전부 지운다")
	void deletesAllOfMember() {
		listener.onWithdrawn(new MemberWithdrawnEvent(1L));

		then(memberFollowRepository).should().deleteAllOf(1L);
		then(memberFollowRequestRepository).should().deleteAllOf(1L);
	}
}

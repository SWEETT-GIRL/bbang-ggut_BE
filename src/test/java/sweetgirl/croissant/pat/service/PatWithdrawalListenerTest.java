package sweetgirl.croissant.pat.service;

import static org.mockito.BDDMockito.then;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.member.event.MemberWithdrawnEvent;
import sweetgirl.croissant.pat.repository.MemberPatRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("PatWithdrawalListener")
class PatWithdrawalListenerTest {

	@Mock
	private MemberPatRepository memberPatRepository;

	@InjectMocks
	private PatWithdrawalListener listener;

	@Test
	@DisplayName("탈퇴 이벤트를 받으면 그 사람의 행을 전부 지운다")
	void deletesAllOfMember() {
		listener.onWithdrawn(new MemberWithdrawnEvent(1L));

		then(memberPatRepository).should().deleteAllOf(1L);
	}
}

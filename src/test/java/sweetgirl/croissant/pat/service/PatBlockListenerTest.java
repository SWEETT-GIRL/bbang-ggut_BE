package sweetgirl.croissant.pat.service;

import static org.mockito.BDDMockito.then;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.member.event.MemberBlockedEvent;
import sweetgirl.croissant.pat.repository.MemberPatRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("PatBlockListener")
class PatBlockListenerTest {

	@Mock
	private MemberPatRepository memberPatRepository;

	@InjectMocks
	private PatBlockListener listener;

	@Test
	@DisplayName("차단 이벤트를 받으면 두 사람 사이 톡을 양방향으로 지운다")
	void deletesBothDirections() {
		listener.onBlocked(new MemberBlockedEvent(1L, 2L));

		then(memberPatRepository).should().deleteBetween(1L, 2L);
	}
}

package sweetgirl.croissant.follow.service;

import static org.mockito.BDDMockito.then;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.member.event.MemberBlockedEvent;

@ExtendWith(MockitoExtension.class)
@DisplayName("FollowBlockListener")
class FollowBlockListenerTest {

	@Mock
	private MemberFollowRepository memberFollowRepository;

	@InjectMocks
	private FollowBlockListener followBlockListener;

	@Test
	@DisplayName("차단 이벤트를 받으면 두 사람 사이 팔로우를 양방향으로 지운다")
	void deletesBothDirections() {
		followBlockListener.onBlocked(new MemberBlockedEvent(1L, 2L));

		then(memberFollowRepository).should().deleteBetween(1L, 2L);
	}
}

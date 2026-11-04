package sweetgirl.croissant.follow.service;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.follow.repository.MemberFollowRequestRepository;
import sweetgirl.croissant.member.event.MemberBlockedEvent;

@ExtendWith(MockitoExtension.class)
@DisplayName("FollowBlockListener")
class FollowBlockListenerTest {

	@Mock
	private MemberFollowRepository memberFollowRepository;
	@Mock
	private MemberFollowRequestRepository memberFollowRequestRepository;
	@Mock
	private sweetgirl.croissant.alarm.service.CommunityNotificationService communityNotificationService;

	@InjectMocks
	private FollowBlockListener followBlockListener;

	@Test
	@DisplayName("차단 이벤트를 받으면 두 사람 사이 팔로우와 팔로우 요청을 양방향으로 지운다")
	void deletesBothDirections() {
		followBlockListener.onBlocked(new MemberBlockedEvent(1L, 2L));

		then(memberFollowRepository).should().deleteBetween(1L, 2L);
		then(memberFollowRequestRepository).should().deleteBetween(1L, 2L);
		then(communityNotificationService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("지운 요청이 있었으면 양쪽 알림함의 요청 알림도 거둔다")
	void retractsRequestNotifications() {
		given(memberFollowRequestRepository.deleteBetween(1L, 2L)).willReturn(1);

		followBlockListener.onBlocked(new MemberBlockedEvent(1L, 2L));

		then(communityNotificationService).should().retractFollowRequestNotification(1L, 2L);
		then(communityNotificationService).should().retractFollowRequestNotification(2L, 1L);
	}
}

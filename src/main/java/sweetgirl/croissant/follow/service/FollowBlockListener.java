package sweetgirl.croissant.follow.service;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.service.CommunityNotificationService;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.follow.repository.MemberFollowRequestRepository;
import sweetgirl.croissant.member.event.MemberBlockedEvent;

/**
 * 차단하면 두 사람 사이 팔로우와 팔로우 요청을 양방향으로 지운다. 해제해도 되살리지 않는다.
 *
 * <p>{@code @EventListener} 라 차단 트랜잭션 안에서 바로 돈다 — 차단은 됐는데 팔로우만 남는 틈이 없다.
 * member 패키지가 follow 를 모르게 하려고 이벤트로 받는다.
 */
@Component
@RequiredArgsConstructor
public class FollowBlockListener {

	private final MemberFollowRepository memberFollowRepository;
	private final MemberFollowRequestRepository memberFollowRequestRepository;
	private final CommunityNotificationService communityNotificationService;

	@EventListener
	public void onBlocked(MemberBlockedEvent event) {
		memberFollowRepository.deleteBetween(event.blockerId(), event.blockedMemberId());
		if (memberFollowRequestRepository.deleteBetween(event.blockerId(), event.blockedMemberId()) > 0) {
			// 어느 쪽이 보낸 요청이든 받은 쪽 알림함에서 거둔다
			communityNotificationService.retractFollowRequestNotification(event.blockerId(), event.blockedMemberId());
			communityNotificationService.retractFollowRequestNotification(event.blockedMemberId(), event.blockerId());
		}
	}
}

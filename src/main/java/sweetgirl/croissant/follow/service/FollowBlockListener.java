package sweetgirl.croissant.follow.service;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.member.event.MemberBlockedEvent;

/**
 * 차단하면 두 사람 사이 팔로우를 양방향으로 지운다. 해제해도 되살리지 않는다.
 *
 * <p>{@code @EventListener} 라 차단 트랜잭션 안에서 바로 돈다 — 차단은 됐는데 팔로우만 남는 틈이 없다.
 * member 패키지가 follow 를 모르게 하려고 이벤트로 받는다.
 */
@Component
@RequiredArgsConstructor
public class FollowBlockListener {

	private final MemberFollowRepository memberFollowRepository;

	@EventListener
	public void onBlocked(MemberBlockedEvent event) {
		memberFollowRepository.deleteBetween(event.blockerId(), event.blockedMemberId());
	}
}

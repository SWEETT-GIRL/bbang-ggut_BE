package sweetgirl.croissant.follow.service;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.member.event.MemberWithdrawnEvent;

/** 탈퇴하면 그 사람의 팔로우를 양방향으로 지운다 — 팔로워 · 팔로잉 수에 탈퇴자가 남지 않게. */
@Component
@RequiredArgsConstructor
public class FollowWithdrawalListener {

	private final MemberFollowRepository memberFollowRepository;

	@EventListener
	public void onWithdrawn(MemberWithdrawnEvent event) {
		memberFollowRepository.deleteAllOf(event.memberId());
	}
}

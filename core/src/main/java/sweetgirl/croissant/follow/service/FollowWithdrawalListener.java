package sweetgirl.croissant.follow.service;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.follow.repository.MemberFollowRequestRepository;
import sweetgirl.croissant.member.event.MemberWithdrawnEvent;

/** 탈퇴하면 그 사람의 팔로우와 팔로우 요청을 양방향으로 지운다 — 팔로워 · 팔로잉 수 · 받은 요청에 탈퇴자가 남지 않게. */
@Component
@RequiredArgsConstructor
public class FollowWithdrawalListener {

	private final MemberFollowRepository memberFollowRepository;
	private final MemberFollowRequestRepository memberFollowRequestRepository;

	@EventListener
	public void onWithdrawn(MemberWithdrawnEvent event) {
		memberFollowRepository.deleteAllOf(event.memberId());
		memberFollowRequestRepository.deleteAllOf(event.memberId());
	}
}

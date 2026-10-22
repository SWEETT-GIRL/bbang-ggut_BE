package sweetgirl.croissant.pat.service;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.event.MemberBlockedEvent;
import sweetgirl.croissant.pat.repository.MemberPatRepository;

/** 차단하면 두 사람 사이 톡을 양방향으로 지운다 — 차단한 사람의 "누가 톡 쳤나"에 상대가 남지 않게. */
@Component
@RequiredArgsConstructor
public class PatBlockListener {

	private final MemberPatRepository memberPatRepository;

	@EventListener
	public void onBlocked(MemberBlockedEvent event) {
		memberPatRepository.deleteBetween(event.blockerId(), event.blockedMemberId());
	}
}

package sweetgirl.croissant.breadlog.service;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.repository.BreadRecordCompanionRepository;
import sweetgirl.croissant.member.event.MemberBlockedEvent;

/** 차단하면 두 사람 사이 같이 간 사람 태그를 양방향으로 지운다 — 어느 쪽 빵로그에도 상대가 남지 않게. */
@Component
@RequiredArgsConstructor
public class CompanionBlockListener {

	private final BreadRecordCompanionRepository companionRepository;

	@EventListener
	public void onBlocked(MemberBlockedEvent event) {
		companionRepository.deleteBetween(event.blockerId(), event.blockedMemberId());
	}
}

package sweetgirl.croissant.pat.service;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.event.MemberWithdrawnEvent;
import sweetgirl.croissant.pat.repository.MemberPatRepository;

/** 탈퇴하면 그 사람의 톡을 양방향으로 지운다. */
@Component
@RequiredArgsConstructor
public class PatWithdrawalListener {

	private final MemberPatRepository memberPatRepository;

	@EventListener
	public void onWithdrawn(MemberWithdrawnEvent event) {
		memberPatRepository.deleteAllOf(event.memberId());
	}
}

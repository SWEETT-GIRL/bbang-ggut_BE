package sweetgirl.croissant.personalization.event;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.member.event.GuestMergedEvent;
import sweetgirl.croissant.personalization.service.UserTasteProfileCommandService;

/**
 * 게스트 → 회원 병합이 커밋되면 회원 프로필을 다시 계산한다(#663 ④). 신호 행은 병합 트랜잭션이 이미 옮겼고 게스트 프로필은 지웠으므로,
 * 여기서는 옮겨진 신호로 회원 축을 새로 만든다. 실패해도 로그만 — 04:00 배치가 같은 회원을 다시 계산한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GuestMergedEventListener {

	private final UserTasteProfileCommandService userTasteProfileCommandService;

	@Async("logExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onGuestMerged(GuestMergedEvent event) {
		try {
			userTasteProfileCommandService.recompute(event.targetId());
		} catch (RuntimeException e) {
			log.warn("[개인화] 게스트 병합 뒤 프로필 재계산 실패 — 04:00 배치에서 재시도 guestId={} targetId={}", event.guestId(),
				event.targetId(), e);
		}
	}
}

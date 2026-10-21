package sweetgirl.croissant.letter.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.event.MemberSignedUpEvent;

/**
 * 가입이 커밋된 뒤에 인사 편지를 넣는다.
 *
 * <p>같은 트랜잭션에 묶지 않는 이유: 편지 적재가 실패했다고 가입까지 롤백되면, 사용자는
 * 편지함 버그 때문에 로그인을 못 한다. 인사는 부수적인 일이라 실패해도 삼키고 로그만
 * 남긴다(빵투어 저장 알림 리스너와 같은 규칙).
 */
@Component
@RequiredArgsConstructor
public class WelcomeLetterListener {

	private static final Logger log = LoggerFactory.getLogger(WelcomeLetterListener.class);

	private final WelcomeLetterService welcomeLetterService;

	@Async
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void handle(MemberSignedUpEvent event) {
		try {
			welcomeLetterService.sendWelcome(event.memberId());
		} catch (RuntimeException e) {
			log.warn("인사 편지 적재 실패 [memberId={}]", event.memberId(), e);
		}
	}
}

package sweetgirl.croissant.alarm.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.event.CourseSavedEvent;

/**
 * 찜하기가 커밋된 뒤에 알림을 쌓는다.
 *
 * <p>같은 트랜잭션에 묶지 않는 이유: 알림 적재가 실패했다고 찜하기까지 롤백되면,
 * 사용자는 알림 버그 때문에 코스를 저장하지 못한다. 알림은 부수적인 일이라
 * 실패해도 삼키고 로그만 남긴다(운영 알림 리스너와 같은 규칙).
 */
@Component
@RequiredArgsConstructor
public class CourseSaveNotificationListener {

	private static final Logger log = LoggerFactory.getLogger(CourseSaveNotificationListener.class);

	private final CourseSaveNotificationService courseSaveNotificationService;

	@Async
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void handle(CourseSavedEvent event) {
		try {
			courseSaveNotificationService.record(event.courseId(), event.actorId(), event.ownerId());
		} catch (RuntimeException e) {
			log.warn("코스 찜 알림 적재 실패 [courseId={}, actorId={}]", event.courseId(), event.actorId(), e);
		}
	}
}

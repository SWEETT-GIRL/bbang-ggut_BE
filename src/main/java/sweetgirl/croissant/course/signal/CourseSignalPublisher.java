package sweetgirl.croissant.course.signal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.course.draft.CourseDraftConfig;
import sweetgirl.croissant.course.draft.MemoDraftRedis;
import sweetgirl.croissant.course.event.CourseChangedEvent;
import sweetgirl.croissant.course.event.CourseDeletedEvent;
import sweetgirl.croissant.course.event.CourseMemberLeftEvent;
import sweetgirl.croissant.course.event.MemoDraftWrittenEvent;

/**
 * 도메인 이벤트를 코스 신호로 바꿔 초안 Redis 의 pub/sub 채널에 싣는다. 서버가 몇 대든 모든 인스턴스의 {@link CourseSignalRelay} 가
 * 받아 자기 소켓 세션에 흘린다. DB 를 바꾸는 이벤트는 커밋 뒤에만 낸다 — 롤백된 변경을 알리면 앱이 헛걸음한다.
 * 신호는 "있으면 빠르고 없어도 폴링이 메우는" 것이라 Redis 실패는 로그만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression(CourseDraftConfig.REDIS_CONFIGURED)
public class CourseSignalPublisher {

	public static final String CHANNEL = "course:signal";

	private final MemoDraftRedis redis;
	private final ObjectMapper objectMapper;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onCourseChanged(CourseChangedEvent event) {
		publish(CourseSignal.version(event.courseId()));
	}

	/** 초안 쓰기는 트랜잭션이 없다 — 받아들여진 즉시 낸다. */
	@EventListener
	public void onDraftWritten(MemoDraftWrittenEvent event) {
		publish(CourseSignal.draft(event.courseId(), event.storeId(), event.memberId(), event.content(), event.seq()));
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onMemberLeft(CourseMemberLeftEvent event) {
		publish(CourseSignal.revoke(event.courseId(), event.memberId()));
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onCourseDeleted(CourseDeletedEvent event) {
		publish(CourseSignal.revoke(event.courseId(), null));
	}

	void publish(CourseSignal signal) {
		try {
			redis.template().convertAndSend(CHANNEL, objectMapper.writeValueAsString(signal));
		} catch (JsonProcessingException | DataAccessException exception) {
			log.warn("[코스 신호] 발행 실패 — 폴링이 메운다 type={} courseId={} cause={}", signal.type(), signal.courseId(),
				exception.toString());
		}
	}
}

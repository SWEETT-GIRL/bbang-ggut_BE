package sweetgirl.croissant.course.draft;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.dto.request.MemoDraftWriteRequest;
import sweetgirl.croissant.course.dto.response.MemoDraftWriteResponse;
import sweetgirl.croissant.course.entity.CourseStoreMemo;
import sweetgirl.croissant.course.event.MemoDraftWrittenEvent;
import sweetgirl.croissant.course.exception.CourseException;

/**
 * 메모 초안 쓰기 — Redis 만 쓴다. 코스 행 락·DB 쓰기가 없어서 한 코스의 편집자 전원이 0.5초마다 불러도 줄을 서지 않는다.
 * 정류장이 이 코스 것인지는 여기서 보지 않는다 — 플러셔와 GET drafts 가 거른다(틀려도 엉뚱한 초안이 최대 10초 머물 뿐).
 * 트랜잭션이 없다: DB 는 권한 캐시가 빠졌을 때만 읽는다 — 로그인 판정까지 캐시 안에 있다(밖에 두면 요청마다 DB 1회).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnExpression(CourseDraftConfig.REDIS_CONFIGURED)
public class MemoDraftCommandService {

	private final CourseDraftProperties properties;
	private final MemoDraftRateLimiter rateLimiter;
	private final MemoDraftAccessCache accessCache;
	private final MemoDraftBuffer buffer;
	private final ApplicationEventPublisher eventPublisher;
	private final MemoDraftMetrics metrics;

	/**
	 * @return 받아들여진 seq. 옛 seq 였으면 Redis 의 현재 seq — 앱은 자기 시계를 그 위로 올린다
	 * @throws CourseException 스위치 꺼짐·Redis 장애 503(앱은 PATCH 로 물러난다), 초당 한도 429, seq 가 하루 넘게 미래 400
	 */
	public MemoDraftWriteResponse write(Long memberId, Long courseId, Long storeId, MemoDraftWriteRequest request) {
		if (!properties.isWriteEnabled()) {
			throw new CourseException(CourseErrorCode.COURSE_DRAFT_UNAVAILABLE);
		}
		rateLimiter.check(memberId);
		try {
			accessCache.requireEditable(courseId, memberId);
		} catch (DataAccessException | TransactionException exception) {
			// 캐시가 빠진 순간 DB(커넥션 풀)가 바쁘면 — 500 이 아니라 503 으로 물려 앱이 PATCH 로 물러나게
			log.warn("[메모 초안] 권한 판정 DB 실패 — 503 courseId={} memberId={} cause={}", courseId, memberId,
				exception.toString());
			throw new CourseException(CourseErrorCode.COURSE_DRAFT_UNAVAILABLE);
		}

		long now = System.currentTimeMillis();
		long seq = request.getSeq();
		if (seq > now + CourseStoreMemo.SEQ_MAX_AHEAD.toMillis()) {
			throw new CourseException(CourseErrorCode.COURSE_MEMO_SEQ_INVALID);
		}
		String content = request.getContent() == null ? "" : request.getContent();
		DraftField field = new DraftField(storeId, memberId);

		long accepted;
		try {
			accepted = buffer.write(courseId, field, content, seq, now);
		} catch (DataAccessException exception) {
			// 연결 실패·타임아웃·noeviction 메모리 초과 전부 — 앱은 로컬에 들고 있다가 PATCH 로 물러난다
			// 바깥 예외는 늘 RedisSystemException 이라 뿌리 원인(READONLY·NOSCRIPT·timeout …)을 같이 남긴다 — 페일오버 분석용
			log.warn("[메모 초안] Redis 쓰기 실패 — 503 으로 물린다 courseId={} memberId={} cause={} root={}", courseId,
				memberId, exception.toString(), NestedExceptionUtils.getMostSpecificCause(exception).toString());
			throw new CourseException(CourseErrorCode.COURSE_DRAFT_UNAVAILABLE);
		}
		metrics.recordWrite(accepted == seq);
		if (accepted == seq) {
			eventPublisher.publishEvent(new MemoDraftWrittenEvent(courseId, storeId, memberId, content, seq));
		}
		return new MemoDraftWriteResponse(accepted);
	}
}

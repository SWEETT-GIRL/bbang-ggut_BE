package sweetgirl.croissant.store.service.trending;

import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.config.async.AsyncConfig;
import sweetgirl.croissant.course.event.CourseStoreAddedEvent;
import sweetgirl.croissant.store.enums.TrendingSignal;
import sweetgirl.croissant.store.event.StoreSavedEvent;
import sweetgirl.croissant.store.event.StoreTrendingSignalEvent;

/**
 * 가게에 대한 관심 신호를 커밋 뒤에 비동기로 센다 — 요청 응답에 Redis 왕복을 얹지 않는다. 찜·코스담기는 취향 적재(InteractionEventListener)가
 * 이미 받는 이벤트를 함께 받는다.
 */
@Component
@RequiredArgsConstructor
public class TrendingSignalListener {

	private final TrendingSignalRecorder recorder;

	@Async(AsyncConfig.TRENDING_EXECUTOR)
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onSignal(StoreTrendingSignalEvent event) {
		recorder.record(event.memberId(), event.storeId(), event.signal(), LocalDateTime.now());
	}

	@Async(AsyncConfig.TRENDING_EXECUTOR)
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onStoreSaved(StoreSavedEvent event) {
		recorder.record(event.memberId(), event.storeId(), TrendingSignal.SAVE, LocalDateTime.now());
	}

	@Async(AsyncConfig.TRENDING_EXECUTOR)
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onCourseStoreAdded(CourseStoreAddedEvent event) {
		recorder.record(event.memberId(), event.storeId(), TrendingSignal.COURSE_ADD, LocalDateTime.now());
	}
}

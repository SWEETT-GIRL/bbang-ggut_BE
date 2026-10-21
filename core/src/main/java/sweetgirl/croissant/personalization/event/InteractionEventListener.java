package sweetgirl.croissant.personalization.event;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.config.async.AsyncConfig;
import sweetgirl.croissant.course.event.CourseStoreAddedEvent;
import sweetgirl.croissant.personalization.entity.UserStoreInteraction;
import sweetgirl.croissant.personalization.enums.InteractionType;
import sweetgirl.croissant.personalization.repository.UserStoreInteractionRepository;
import sweetgirl.croissant.store.event.StoreSavedEvent;
import sweetgirl.croissant.store.event.StoreUnsavedEvent;

/**
 * 다른 도메인이 남기는 신호를 이벤트로 받아 user_store_interaction 에 쓴다 (#663 · #731 패키지 경계 — 서비스끼리 주입하면 순환 참조).
 * 원본 표에 흔적이 안 남는 저장 해제, 그와 짝을 맞추는 저장, 담은 사람이 필요한 담기 세 가지다. 커밋 뒤에 비동기로 쓰고, 실패는 로그만 남긴다 — 신호를
 * 잃는 것이 저장 해제 · 담기 요청을 깨뜨리는 것보다 낫다(SearchMissEventListener 와 같은 이유). logExecutor 는 큐가 차면 버린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InteractionEventListener {

	static final String CONTEXT_RECOMMENDATION = "RECO";

	private final UserStoreInteractionRepository userStoreInteractionRepository;

	@Async(AsyncConfig.SIGNAL_EXECUTOR)
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onStoreSaved(StoreSavedEvent event) {
		record(UserStoreInteraction.of(event.memberId(), event.storeId(), null, InteractionType.SAVE.name(), null,
			null, event.savedAt()), event);
	}

	@Async(AsyncConfig.SIGNAL_EXECUTOR)
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onStoreUnsaved(StoreUnsavedEvent event) {
		record(UserStoreInteraction.of(event.memberId(), event.storeId(), null, InteractionType.UNSAVE.name(), null,
			null, event.unsavedAt()), event);
	}

	@Async(AsyncConfig.SIGNAL_EXECUTOR)
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onCourseStoreAdded(CourseStoreAddedEvent event) {
		String context = event.recommendationId() != null ? CONTEXT_RECOMMENDATION : null;
		record(UserStoreInteraction.of(event.memberId(), event.storeId(), null, InteractionType.COURSE_ADD.name(),
			context, null, event.addedAt()), event);
	}

	private void record(UserStoreInteraction interaction, Object event) {
		try {
			userStoreInteractionRepository.save(interaction);
		} catch (DataIntegrityViolationException e) {
			// 같은 사건이 이미 있다(재시도 · 백필과 겹침). 유니크 제약이 맞게 막은 것이라 그대로 둔다
			log.debug("신호 중복 무시 event={}", event);
		} catch (RuntimeException e) {
			log.warn("신호 적재 실패 event={}", event, e);
		}
	}
}

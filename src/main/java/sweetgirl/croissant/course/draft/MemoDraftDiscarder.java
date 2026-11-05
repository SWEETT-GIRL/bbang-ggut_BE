package sweetgirl.croissant.course.draft;

import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PATCH 가 메모를 DB 에 바로 반영한 뒤, 같은 칸의 그보다 새롭지 않은 초안을 Redis 에서 지운다.
 *
 * <p>초안 Redis 가 없는 환경에서도 PATCH 는 돌아야 하므로 버퍼를 {@link Optional} 로 받는다. 커밋 뒤에 지운다 — 커밋 전에 지우고
 * 롤백되면 초안도 DB 도 없는 상태가 된다. 지우기가 실패해도 PATCH 는 성공이다: 플러셔가 그 초안을 다시 반영해도 seq 가 걸러 결과는 같다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoDraftDiscarder {

	private final Optional<MemoDraftBuffer> buffer;

	public void discardAfterCommit(Long courseId, Long storeId, Long memberId, long seq) {
		if (buffer.isEmpty()) {
			return;
		}
		Runnable discard = () -> discard(courseId, new DraftField(storeId, memberId), seq);
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					discard.run();
				}
			});
			return;
		}
		discard.run();
	}

	private void discard(Long courseId, DraftField field, long seq) {
		try {
			buffer.get().discard(courseId, field, seq);
		} catch (DataAccessException exception) {
			log.warn("[메모 초안] PATCH 뒤 초안 지우기 실패 — 플러셔가 seq 로 거른다 courseId={} field={} cause={}", courseId,
				field.key(), exception.toString());
		}
	}
}

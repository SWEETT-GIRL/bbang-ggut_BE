package sweetgirl.croissant.store.service.search.cache;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.store.event.StorePopularityRefreshedEvent;
import sweetgirl.croissant.store.event.StoreSearchNameChangedEvent;
import sweetgirl.croissant.store.event.StoreTrendingBaselineRefreshedEvent;

/**
 * 가게 변경을 커밋 뒤에 자동완성 캐시에 반영한다. 커밋 전에 지우면, 그 사이 들어온 요청이 아직 안 바뀐 DB 를 읽어 옛 결과를 다시 채운다.
 *
 * <p>fallbackExecution: 트랜잭션 밖에서 발행해도(테스트·비트랜잭션 경로) 바로 지운다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutocompleteCacheEvictListener {

	private final AutocompleteCacheInvalidator invalidator;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onStoreSearchNameChanged(StoreSearchNameChangedEvent event) {
		invalidator.evictByNames(event.names());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onStorePopularityRefreshed(StorePopularityRefreshedEvent event) {
		long evicted = invalidator.evictAll();
		log.info("[자동완성 캐시] 인기도 갱신으로 순서가 바뀌어 전체를 비웠다 updatedStores={} evictedKeys={}",
			event.updatedStoreCount(), evicted);
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onStoreTrendingBaselineRefreshed(StoreTrendingBaselineRefreshedEvent event) {
		long evicted = invalidator.evictAll();
		log.info("[자동완성 캐시] 평소 수준 갱신으로 스테디 순서가 바뀌어 전체를 비웠다 updatedStores={} evictedKeys={}",
			event.updatedStoreCount(), evicted);
	}
}

package sweetgirl.croissant.searchmiss.event;

import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.searchmiss.service.SearchMissCommandService;

/**
 * 실패한 검색어를 비동기로 적재한다.
 *
 * <p>{@code logExecutor}는 큐가 차면 버리는(DiscardPolicy) 풀이다. 검색어 기록을 잃는 것보다
 * 검색이 느려지거나 실패하는 것이 훨씬 나쁘므로 의도된 선택이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchMissEventListener {

	private final SearchMissCommandService searchMissCommandService;

	@Async("logExecutor")
	@EventListener
	public void onSearchMiss(SearchMissEvent event) {
		try {
			searchMissCommandService.record(event.keyword(), event.outcome(), event.occurredAt());
		} catch (DataIntegrityViolationException e) {
			// 같은 검색어가 동시에 처음 들어와 다른 스레드가 먼저 INSERT했다.
			// 앞선 트랜잭션은 롤백됐으므로 새 트랜잭션에서 다시 부르면 이번엔 UPDATE로 처리된다.
			retryRecord(event);
		} catch (RuntimeException e) {
			// 적재 실패가 검색을 깨뜨리면 안 된다. 비동기 경계라 여기서 끊는다.
			log.warn("검색어 적재 실패 keyword={}", event.keyword(), e);
		}
	}

	private void retryRecord(SearchMissEvent event) {
		try {
			searchMissCommandService.record(event.keyword(), event.outcome(), event.occurredAt());
		} catch (RuntimeException e) {
			log.warn("검색어 적재 재시도 실패 keyword={}", event.keyword(), e);
		}
	}
}

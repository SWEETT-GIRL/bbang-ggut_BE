package sweetgirl.croissant.searchmiss.event;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import sweetgirl.croissant.searchmiss.service.SearchMissCommandService;
import sweetgirl.croissant.store.service.search.SearchOutcome;

@ExtendWith(MockitoExtension.class)
class SearchMissEventListenerTest {

	private static final SearchMissEvent EVENT = new SearchMissEvent(
		"성수동 크로플", SearchOutcome.EMPTY, LocalDateTime.of(2026, 8, 11, 15, 0));

	@Mock
	private SearchMissCommandService searchMissCommandService;

	@InjectMocks
	private SearchMissEventListener searchMissEventListener;

	@Test
	@DisplayName("이벤트를 받으면 적재 서비스에 넘긴다")
	void onSearchMiss_delegatesToCommandService() {
		searchMissEventListener.onSearchMiss(EVENT);

		verify(searchMissCommandService).record(
			eq("성수동 크로플"), eq(SearchOutcome.EMPTY), eq(EVENT.occurredAt()));
	}

	@Test
	@DisplayName("동시 삽입으로 unique 제약에 걸리면 한 번 재시도한다")
	void onSearchMiss_retriesOnUniqueViolation() {
		willThrow(new DataIntegrityViolationException("duplicate key"))
			.willDoNothing()
			.given(searchMissCommandService).record(any(), any(), any());

		searchMissEventListener.onSearchMiss(EVENT);

		// 앞선 트랜잭션은 롤백됐으므로 새로 부르면 이번엔 UPDATE 경로로 처리된다
		verify(searchMissCommandService, times(2)).record(any(), any(), any());
	}

	@Test
	@DisplayName("재시도까지 실패해도 예외를 밖으로 던지지 않는다")
	void onSearchMiss_swallowsExceptionWhenRetryFails() {
		willThrow(new DataIntegrityViolationException("duplicate key"))
			.given(searchMissCommandService).record(any(), any(), any());

		assertThatCode(() -> searchMissEventListener.onSearchMiss(EVENT)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("적재 실패가 검색을 깨뜨리지 않는다")
	void onSearchMiss_swallowsUnexpectedException() {
		willThrow(new IllegalStateException("DB 연결 끊김"))
			.given(searchMissCommandService).record(any(), any(), any());

		assertThatCode(() -> searchMissEventListener.onSearchMiss(EVENT)).doesNotThrowAnyException();
		verify(searchMissCommandService).record(any(), any(), any());
	}
}

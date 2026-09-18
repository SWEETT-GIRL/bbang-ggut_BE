package sweetgirl.croissant.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.config.StorePopularityProperties;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StorePopularityRepository;

@ExtendWith(MockitoExtension.class)
class StorePopularityCommandServiceTest {

	@Mock
	private StorePopularityRepository storePopularityRepository;
	@Spy
	private StorePopularityProperties storePopularityProperties = new StorePopularityProperties();

	@InjectMocks
	private StorePopularityCommandService service;

	@Test
	void refreshAllPopularityScores_returnsUpdatedCount() {
		storePopularityProperties.setConversionSmoothing(7);
		storePopularityProperties.setRankingScaleQuantile(0.9);
		given(storePopularityRepository.tryLockPopularityRefresh(anyLong())).willReturn(true);
		given(storePopularityRepository.refreshAllPopularityScores(7, 0.9)).willReturn(45_339);
		given(storePopularityRepository.findActivePopularityQuantile(anyDouble())).willReturn(4.3);

		int updated = service.refreshAllPopularityScores();

		assertThat(updated).isEqualTo(45_339);
		verify(storePopularityRepository).refreshAllPopularityScores(7, 0.9); // 설정값이 그대로 전달된다
	}

	@Test
	void refreshAllPopularityScores_throwsConflictWhenAnotherRefreshIsRunning() {
		// 어드민 수동 갱신과 주간 배치(또는 다중 인스턴스)가 겹치면 두 번째는 UPDATE 를 시작하지 않고 409
		given(storePopularityRepository.tryLockPopularityRefresh(anyLong())).willReturn(false);

		assertThatThrownBy(() -> service.refreshAllPopularityScores())
			.isInstanceOf(StoreException.class)
			.hasMessageContaining(StoreErrorCode.POPULARITY_REFRESH_ALREADY_RUNNING.getMessage());
		verify(storePopularityRepository, never()).refreshAllPopularityScores(anyInt(), anyDouble());
	}

	@Test
	void refreshAllPopularityScores_propagatesRepositoryFailure() {
		// 삼키지 않는다 — 어드민 호출은 500 으로, 스케줄러는 자기 계층에서 로깅한다.
		given(storePopularityRepository.tryLockPopularityRefresh(anyLong())).willReturn(true);
		given(storePopularityRepository.refreshAllPopularityScores(anyInt(), anyDouble()))
			.willThrow(new DataAccessResourceFailureException("db down"));

		assertThatThrownBy(() -> service.refreshAllPopularityScores())
			.isInstanceOf(DataAccessResourceFailureException.class);
	}
}

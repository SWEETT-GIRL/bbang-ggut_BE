package sweetgirl.croissant.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import sweetgirl.croissant.store.repository.StorePopularityRepository;

@ExtendWith(MockitoExtension.class)
class StorePopularityCommandServiceTest {

	@Mock
	private StorePopularityRepository storePopularityRepository;

	@InjectMocks
	private StorePopularityCommandService service;

	@Test
	void refreshAllPopularityScores_returnsUpdatedCount() {
		given(storePopularityRepository.refreshAllPopularityScores()).willReturn(45_339);

		int updated = service.refreshAllPopularityScores();

		assertThat(updated).isEqualTo(45_339);
		verify(storePopularityRepository).refreshAllPopularityScores();
	}

	@Test
	void refreshAllPopularityScores_propagatesRepositoryFailure() {
		// 삼키지 않는다 — 어드민 호출은 500 으로, 스케줄러는 자기 계층에서 로깅한다.
		given(storePopularityRepository.refreshAllPopularityScores())
			.willThrow(new DataAccessResourceFailureException("db down"));

		assertThatThrownBy(() -> service.refreshAllPopularityScores())
			.isInstanceOf(DataAccessResourceFailureException.class);
	}
}

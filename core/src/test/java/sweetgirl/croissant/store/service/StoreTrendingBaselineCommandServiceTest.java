package sweetgirl.croissant.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.config.StoreTrendingProperties;
import sweetgirl.croissant.store.enums.TrendingSignal;
import sweetgirl.croissant.store.event.StoreTrendingBaselineRefreshedEvent;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.trending.StoreTrendingRepository;

@ExtendWith(MockitoExtension.class)
class StoreTrendingBaselineCommandServiceTest {

	@Mock
	private StoreTrendingRepository storeTrendingRepository;
	@Mock
	private ApplicationEventPublisher eventPublisher;

	private final StoreTrendingProperties properties = new StoreTrendingProperties();
	private StoreTrendingBaselineCommandService service;

	@BeforeEach
	void setUp() {
		service = new StoreTrendingBaselineCommandService(storeTrendingRepository, properties, eventPublisher);
	}

	@Test
	@DisplayName("최근 7일 바로 앞 28일을 신호 종류별 가중치로 세고, 자동완성 캐시를 비우도록 이벤트를 낸다")
	@SuppressWarnings("unchecked")
	void refreshBaselines_usesWindowBeforeRecentAndPublishesEvent() {
		given(storeTrendingRepository.tryLock(anyLong())).willReturn(true);
		given(storeTrendingRepository.refreshBaselines(anyMap(), any(), any(), eq(28L))).willReturn(120);

		int updated = service.refreshBaselines(LocalDate.of(2026, 10, 5));

		ArgumentCaptor<Map<String, Double>> weights = ArgumentCaptor.forClass(Map.class);
		// 10-05 기준: 최근 창 [09-28, 10-05) 은 빼고 그 앞 28일 [08-31, 09-28)
		verify(storeTrendingRepository).refreshBaselines(weights.capture(),
			eq(LocalDateTime.of(2026, 8, 31, 0, 0)), eq(LocalDateTime.of(2026, 9, 28, 0, 0)), eq(28L));
		assertThat(weights.getValue()).containsEntry("STORE_VIEW", 1.0).containsEntry("SAVE", 2.0)
			.containsEntry("PLAN", 2.0).containsEntry("COURSE_ADD", 2.0).containsEntry("RECORD_STORE", 3.0);
		assertThat(updated).isEqualTo(120);
		verify(eventPublisher).publishEvent(new StoreTrendingBaselineRefreshedEvent(120));
	}

	@Test
	@DisplayName("가중치를 0 으로 둔 신호는 세지 않는다")
	@SuppressWarnings("unchecked")
	void refreshBaselines_skipsZeroWeightSignals() {
		properties.getWeights().put(TrendingSignal.VIEW, 0.0);
		given(storeTrendingRepository.tryLock(anyLong())).willReturn(true);

		service.refreshBaselines(LocalDate.of(2026, 10, 5));

		ArgumentCaptor<Map<String, Double>> weights = ArgumentCaptor.forClass(Map.class);
		verify(storeTrendingRepository).refreshBaselines(weights.capture(), any(), any(), eq(28L));
		assertThat(weights.getValue()).doesNotContainKey("STORE_VIEW");
	}

	@Test
	@DisplayName("다른 갱신이 돌고 있으면 409 로 거절하고 계산하지 않는다")
	void refreshBaselines_throwsConflictWhenLocked() {
		given(storeTrendingRepository.tryLock(anyLong())).willReturn(false);

		assertThatThrownBy(() -> service.refreshBaselines(LocalDate.of(2026, 10, 5)))
			.isInstanceOf(StoreException.class)
			.hasMessageContaining(StoreErrorCode.TRENDING_BASELINE_REFRESH_ALREADY_RUNNING.getMessage());
		verify(storeTrendingRepository, never()).refreshBaselines(anyMap(), any(), any(), anyLong());
		verify(eventPublisher, never()).publishEvent(any(StoreTrendingBaselineRefreshedEvent.class));
	}
}

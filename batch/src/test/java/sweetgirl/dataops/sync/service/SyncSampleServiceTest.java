package sweetgirl.dataops.sync.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import sweetgirl.dataops.serving.client.ServingReader;
import sweetgirl.dataops.serving.exception.ServingException;
import sweetgirl.dataops.sync.dto.response.ObservationSampleResponse;
import sweetgirl.dataops.sync.enums.SyncSource;
import sweetgirl.dataops.sync.enums.SyncStateStatus;
import sweetgirl.dataops.sync.repository.StoreSyncStateRepository;

@ExtendWith(MockitoExtension.class)
class SyncSampleServiceTest {

	@Mock
	private ObjectProvider<ServingReader> servingReaderProvider;
	@Mock
	private ServingReader servingReader;
	@Mock
	private StoreSyncStateRepository storeSyncStateRepository;

	@InjectMocks
	private SyncSampleService syncSampleService;

	@Test
	void selectObservationSample_운영_DB_미설정이면_예외를_던진다() {
		given(servingReaderProvider.getIfAvailable()).willReturn(null);

		assertThatThrownBy(() -> syncSampleService.selectObservationSample(1000))
			.isInstanceOf(ServingException.class);
	}

	@Test
	void selectObservationSample_메뉴0_80퍼센트와_메뉴보유_20퍼센트로_뽑는다() {
		List<Long> allStoreIds = LongStream.rangeClosed(1, 100).boxed().toList();
		List<Long> menuZeroIds = LongStream.rangeClosed(1, 50).boxed().toList();
		given(servingReaderProvider.getIfAvailable()).willReturn(servingReader);
		given(servingReader.findMenuZeroMatchedStoreIds()).willReturn(menuZeroIds);
		given(storeSyncStateRepository.findStoreIdsBySource(SyncSource.NAVER)).willReturn(allStoreIds);
		given(storeSyncStateRepository.clearSchedule(SyncSource.NAVER, SyncStateStatus.ACTIVE))
			.willReturn(100);
		given(storeSyncStateRepository.scheduleStores(
			eq(SyncSource.NAVER), eq(SyncStateStatus.ACTIVE), anyList(), any(LocalDateTime.class)))
			.willAnswer(invocation -> ((List<?>)invocation.getArgument(2)).size());

		ObservationSampleResponse response = syncSampleService.selectObservationSample(10);

		assertThat(response.menuZeroCount()).isEqualTo(8);
		assertThat(response.withMenuCount()).isEqualTo(2);
		assertThat(response.scheduledNow()).isEqualTo(10);
		assertThat(response.onHold()).isEqualTo(90);
	}

	@Test
	void selectObservationSample_메뉴0_가게가_부족하면_메뉴보유로_채운다() {
		List<Long> allStoreIds = LongStream.rangeClosed(1, 100).boxed().toList();
		given(servingReaderProvider.getIfAvailable()).willReturn(servingReader);
		given(servingReader.findMenuZeroMatchedStoreIds()).willReturn(List.of(1L, 2L, 3L));
		given(storeSyncStateRepository.findStoreIdsBySource(SyncSource.NAVER)).willReturn(allStoreIds);
		given(storeSyncStateRepository.clearSchedule(any(), any())).willReturn(100);
		given(storeSyncStateRepository.scheduleStores(any(), any(), anyList(), any()))
			.willAnswer(invocation -> ((List<?>)invocation.getArgument(2)).size());

		ObservationSampleResponse response = syncSampleService.selectObservationSample(10);

		assertThat(response.menuZeroCount()).isEqualTo(3);
		assertThat(response.withMenuCount()).isEqualTo(7);
		assertThat(response.sampleSize()).isEqualTo(10);
	}

	@Test
	void selectObservationSample_같은_입력이면_같은_표본이_나온다_결정적() {
		List<Long> allStoreIds = LongStream.rangeClosed(1, 100).boxed().toList();
		List<Long> menuZeroIds = LongStream.rangeClosed(1, 50).boxed().toList();
		given(servingReaderProvider.getIfAvailable()).willReturn(servingReader);
		given(servingReader.findMenuZeroMatchedStoreIds()).willReturn(menuZeroIds);
		given(storeSyncStateRepository.findStoreIdsBySource(SyncSource.NAVER)).willReturn(allStoreIds);
		given(storeSyncStateRepository.clearSchedule(any(), any())).willReturn(100);
		List<List<Long>> capturedSamples = new java.util.ArrayList<>();
		given(storeSyncStateRepository.scheduleStores(any(), any(), anyList(), any()))
			.willAnswer(invocation -> {
				capturedSamples.add(List.copyOf(invocation.getArgument(2)));
				return ((List<?>)invocation.getArgument(2)).size();
			});

		syncSampleService.selectObservationSample(10);
		syncSampleService.selectObservationSample(10);

		assertThat(capturedSamples.get(0)).isEqualTo(capturedSamples.get(1));
	}
}

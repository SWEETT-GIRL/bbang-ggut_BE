package sweetgirl.croissant.store.service.trending;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.course.event.CourseStoreAddedEvent;
import sweetgirl.croissant.store.enums.TrendingSignal;
import sweetgirl.croissant.store.event.StoreSavedEvent;
import sweetgirl.croissant.store.event.StoreTrendingSignalEvent;

@ExtendWith(MockitoExtension.class)
class TrendingSignalListenerTest {

	@Mock
	private TrendingSignalRecorder recorder;

	@InjectMocks
	private TrendingSignalListener listener;

	@Test
	@DisplayName("조회·가고싶은 가게·빵로그 신호는 실린 종류 그대로 센다")
	void signalEvent_isRecordedWithItsType() {
		listener.onSignal(new StoreTrendingSignalEvent(7L, 10L, TrendingSignal.RECORD));

		verify(recorder).record(eq(7L), eq(10L), eq(TrendingSignal.RECORD), any(LocalDateTime.class));
	}

	@Test
	@DisplayName("이미 있는 찜·코스담기 이벤트도 급부상 신호로 받는다")
	void existingEvents_areRecordedAsSaveAndCourseAdd() {
		listener.onStoreSaved(new StoreSavedEvent(7L, 10L, LocalDateTime.now()));
		listener.onCourseStoreAdded(new CourseStoreAddedEvent(7L, 3L, 11L, null, LocalDateTime.now()));

		verify(recorder).record(eq(7L), eq(10L), eq(TrendingSignal.SAVE), any(LocalDateTime.class));
		verify(recorder).record(eq(7L), eq(11L), eq(TrendingSignal.COURSE_ADD), any(LocalDateTime.class));
	}
}

package sweetgirl.croissant.course.draft;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemoDraftFlusher")
class MemoDraftFlusherTest {

	private static final long COURSE_ID = 10L;
	private static final DraftField FIELD = new DraftField(100L, 1L);
	private static final Duration QUIET = Duration.ofSeconds(2);
	private static final Duration RETRY = Duration.ofSeconds(5);

	@Mock
	private MemoDraftBuffer buffer;
	@Mock
	private MemoDraftApplier applier;
	@Mock
	private MemoDraftMetrics metrics;

	private CourseDraftProperties properties;
	private MemoDraftFlusher flusher;

	@BeforeEach
	void setUp() {
		properties = new CourseDraftProperties();
		properties.setQuietWindow(QUIET);
		properties.setRetryDelay(RETRY);
		flusher = new MemoDraftFlusher(properties, buffer, applier, metrics);
	}

	@Test
	@DisplayName("플러셔 스위치가 꺼져 있으면 Redis 를 건드리지 않는다")
	void disabled_doesNothing() {
		properties.setFlusherEnabled(false);

		flusher.tick();

		then(buffer).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("due 가 된 코스를 꺼내 반영하고, 처리한 seq 로 release 한다(남은 건 2초 뒤 재등록)")
	void tick_flushesDueCourses() {
		given(buffer.popDue(anyLong())).willReturn(List.of(COURSE_ID));
		List<DraftEntry> entries = List.of(new DraftEntry(FIELD, "소금빵", 5L, 1L));
		given(buffer.readAll(COURSE_ID)).willReturn(entries);
		given(applier.apply(COURSE_ID, entries)).willReturn(Map.of(FIELD, 5L));

		flusher.tick();

		then(buffer).should().reclaimExpired(anyLong());
		then(buffer).should().release(eq(COURSE_ID), eq(Map.of(FIELD, 5L)), anyLong(), eq(QUIET));
		then(metrics).should().recordFlush(eq(entries), anyLong());
	}

	@Test
	@DisplayName("DB 반영이 실패하면 전부 남기고 retry-delay 뒤 다시 꺼내게 한다")
	void flush_dbFailure_requeuesWithRetryDelay() {
		given(buffer.popDue(anyLong())).willReturn(List.of(COURSE_ID));
		given(buffer.readAll(COURSE_ID)).willReturn(List.of(new DraftEntry(FIELD, "소금빵", 5L, 1L)));
		given(applier.apply(eq(COURSE_ID), any())).willThrow(new DataAccessResourceFailureException("db down"));

		assertThatCode(() -> flusher.tick()).doesNotThrowAnyException();

		then(buffer).should().release(eq(COURSE_ID), eq(Map.of()), anyLong(), eq(RETRY));
	}

	@Test
	@DisplayName("Redis 가 안 되면 이번 틱은 건너뛴다 — 초안은 Redis 에 그대로 남아 있다")
	void tick_redisFailure_skipsTick() {
		given(buffer.reclaimExpired(anyLong())).willThrow(new RedisConnectionFailureException("down"));

		assertThatCode(() -> flusher.tick()).doesNotThrowAnyException();

		then(buffer).should(never()).popDue(anyLong());
		then(applier).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("종료 중에는 꺼낸 코스를 반영하지 않고 바로 due 로 돌려 다른 인스턴스가 가져가게 한다")
	void stopping_releasesWithoutApplying() {
		given(buffer.popDue(anyLong())).willReturn(List.of(COURSE_ID));
		flusher.stop();

		flusher.tick();

		then(applier).shouldHaveNoInteractions();
		then(buffer).should().release(eq(COURSE_ID), eq(Map.of()), anyLong(), eq(Duration.ZERO));
	}
}

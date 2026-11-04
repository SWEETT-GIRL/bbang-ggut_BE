package sweetgirl.croissant.course.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.RedisConnectionFailureException;

import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.dto.request.MemoDraftWriteRequest;
import sweetgirl.croissant.course.dto.response.MemoDraftWriteResponse;
import sweetgirl.croissant.course.entity.CourseStoreMemo;
import sweetgirl.croissant.course.event.MemoDraftWrittenEvent;
import sweetgirl.croissant.course.exception.CourseException;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemoDraftCommandService")
class MemoDraftCommandServiceTest {

	private static final Long MEMBER_ID = 1L;
	private static final Long COURSE_ID = 10L;
	private static final Long STORE_ID = 100L;
	private static final DraftField FIELD = new DraftField(STORE_ID, MEMBER_ID);

	@Mock
	private CourseDraftProperties properties;
	@Mock
	private MemoDraftRateLimiter rateLimiter;
	@Mock
	private MemoDraftAccessCache accessCache;
	@Mock
	private MemoDraftBuffer buffer;
	@Mock
	private ApplicationEventPublisher eventPublisher;
	@Mock
	private MemoDraftMetrics metrics;

	@InjectMocks
	private MemoDraftCommandService memoDraftCommandService;

	private MemoDraftWriteRequest request;

	@BeforeEach
	void setUp() {
		request = mock(MemoDraftWriteRequest.class);
	}

	private void givenWritable(String content, long seq) {
		given(properties.isWriteEnabled()).willReturn(true);
		given(request.getContent()).willReturn(content);
		given(request.getSeq()).willReturn(seq);
	}

	@Test
	@DisplayName("새 seq 가 받아들여지면 Redis 에 쓰고 DRAFT 이벤트를 내용과 함께 낸다")
	void write_accepted_publishesDraftEvent() {
		givenWritable("소금빵 꼭", 1_000L);
		given(buffer.write(eq(COURSE_ID), eq(FIELD), eq("소금빵 꼭"), eq(1_000L), anyLong())).willReturn(1_000L);

		MemoDraftWriteResponse response = memoDraftCommandService.write(MEMBER_ID, COURSE_ID, STORE_ID, request);

		assertThat(response.acceptedSeq()).isEqualTo(1_000L);
		then(rateLimiter).should().check(MEMBER_ID);
		then(accessCache).should().requireEditable(COURSE_ID, MEMBER_ID);
		ArgumentCaptor<MemoDraftWrittenEvent> event = ArgumentCaptor.forClass(MemoDraftWrittenEvent.class);
		then(eventPublisher).should().publishEvent(event.capture());
		assertThat(event.getValue())
			.isEqualTo(new MemoDraftWrittenEvent(COURSE_ID, STORE_ID, MEMBER_ID, "소금빵 꼭", 1_000L));
	}

	@Test
	@DisplayName("옛 seq 는 Redis 가 무시하고 현재 seq 를 돌려준다 — 이벤트는 내지 않는다")
	void write_olderSeq_returnsCurrentWithoutEvent() {
		givenWritable("늦게 온 글", 900L);
		given(buffer.write(eq(COURSE_ID), eq(FIELD), eq("늦게 온 글"), eq(900L), anyLong())).willReturn(1_000L);

		MemoDraftWriteResponse response = memoDraftCommandService.write(MEMBER_ID, COURSE_ID, STORE_ID, request);

		assertThat(response.acceptedSeq()).isEqualTo(1_000L);
		then(eventPublisher).should(never()).publishEvent(any());
	}

	@Test
	@DisplayName("content 가 없으면 빈 문자열(지우기 예약)로 쓴다")
	void write_nullContent_writesBlank() {
		givenWritable(null, 1_000L);
		given(buffer.write(eq(COURSE_ID), eq(FIELD), eq(""), eq(1_000L), anyLong())).willReturn(1_000L);

		memoDraftCommandService.write(MEMBER_ID, COURSE_ID, STORE_ID, request);

		then(buffer).should().write(eq(COURSE_ID), eq(FIELD), eq(""), eq(1_000L), anyLong());
	}

	@Test
	@DisplayName("쓰기 스위치가 꺼져 있으면 503 — Redis 를 건드리지 않는다")
	void write_switchOff_unavailable() {
		given(properties.isWriteEnabled()).willReturn(false);

		assertThatThrownBy(() -> memoDraftCommandService.write(MEMBER_ID, COURSE_ID, STORE_ID, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_DRAFT_UNAVAILABLE.getMessage());
		then(buffer).shouldHaveNoInteractions();
		then(accessCache).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("Redis 장애면 503 — 앱은 로컬에 들고 있다가 PATCH 로 물러난다")
	void write_redisFailure_unavailable() {
		givenWritable("소금빵", 1_000L);
		given(buffer.write(eq(COURSE_ID), eq(FIELD), eq("소금빵"), eq(1_000L), anyLong()))
			.willThrow(new RedisConnectionFailureException("down"));

		assertThatThrownBy(() -> memoDraftCommandService.write(MEMBER_ID, COURSE_ID, STORE_ID, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_DRAFT_UNAVAILABLE.getMessage());
		then(eventPublisher).should(never()).publishEvent(any());
	}

	@Test
	@DisplayName("seq 가 서버 시각보다 하루 넘게 미래면 400 — 시계가 틀린 기기")
	void write_seqTooFarAhead_rejected() {
		given(properties.isWriteEnabled()).willReturn(true);
		given(request.getSeq())
			.willReturn(System.currentTimeMillis() + CourseStoreMemo.SEQ_MAX_AHEAD.toMillis() + 60_000L);

		assertThatThrownBy(() -> memoDraftCommandService.write(MEMBER_ID, COURSE_ID, STORE_ID, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_MEMO_SEQ_INVALID.getMessage());
		then(buffer).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("초당 한도를 넘으면 429 — 권한 판정·Redis 전에 막는다")
	void write_rateLimited_rejectedBeforeAccessCheck() {
		given(properties.isWriteEnabled()).willReturn(true);
		willThrow(new CourseException(CourseErrorCode.COURSE_DRAFT_TOO_FREQUENT)).given(rateLimiter).check(MEMBER_ID);

		assertThatThrownBy(() -> memoDraftCommandService.write(MEMBER_ID, COURSE_ID, STORE_ID, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_DRAFT_TOO_FREQUENT.getMessage());
		then(accessCache).shouldHaveNoInteractions();
		then(buffer).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("권한 판정 중 DB(커넥션 풀)가 바쁘면 503 — 앱이 PATCH 로 물러난다")
	void write_accessDbFailure_unavailable() {
		given(properties.isWriteEnabled()).willReturn(true);
		willThrow(new org.springframework.transaction.CannotCreateTransactionException("pool exhausted"))
			.given(accessCache).requireEditable(COURSE_ID, MEMBER_ID);

		assertThatThrownBy(() -> memoDraftCommandService.write(MEMBER_ID, COURSE_ID, STORE_ID, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_DRAFT_UNAVAILABLE.getMessage());
		then(buffer).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("권한 없음·쓰기 제한은 그대로 올라가고 Redis 에 쓰지 않는다")
	void write_forbidden_propagates() {
		given(properties.isWriteEnabled()).willReturn(true);
		willThrow(new CourseException(CourseErrorCode.COURSE_FORBIDDEN))
			.given(accessCache).requireEditable(COURSE_ID, MEMBER_ID);

		assertThatThrownBy(() -> memoDraftCommandService.write(MEMBER_ID, COURSE_ID, STORE_ID, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
		then(buffer).shouldHaveNoInteractions();
	}
}

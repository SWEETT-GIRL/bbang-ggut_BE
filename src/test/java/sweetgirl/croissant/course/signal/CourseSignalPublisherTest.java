package sweetgirl.croissant.course.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.course.draft.MemoDraftRedis;
import sweetgirl.croissant.course.event.CourseChangedEvent;
import sweetgirl.croissant.course.event.CourseDeletedEvent;
import sweetgirl.croissant.course.event.CourseMemberLeftEvent;
import sweetgirl.croissant.course.event.MemoDraftWrittenEvent;

@DisplayName("CourseSignalPublisher")
class CourseSignalPublisherTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private StringRedisTemplate template;
	private CourseSignalPublisher publisher;

	@BeforeEach
	void setUp() {
		template = mock(StringRedisTemplate.class);
		MemoDraftRedis redis = mock(MemoDraftRedis.class);
		given(redis.template()).willReturn(template);
		publisher = new CourseSignalPublisher(redis, objectMapper);
	}

	private CourseSignal published() throws Exception {
		ArgumentCaptor<Object> json = ArgumentCaptor.forClass(Object.class);
		then(template).should().convertAndSend(eq(CourseSignalPublisher.CHANNEL), json.capture());
		return objectMapper.readValue(json.getValue().toString(), CourseSignal.class);
	}

	@Test
	@DisplayName("초안이 받아들여지면 내용을 실은 DRAFT 를 채널에 낸다")
	void draftWritten_publishesDraftWithContent() throws Exception {
		publisher.onDraftWritten(new MemoDraftWrittenEvent(10L, 100L, 7L, "소금빵 꼭", 1_000L));

		assertThat(published()).isEqualTo(CourseSignal.draft(10L, 100L, 7L, "소금빵 꼭", 1_000L));
	}

	@Test
	@DisplayName("version 이 오르면 내용 없는 VERSION 을 낸다")
	void courseChanged_publishesVersion() throws Exception {
		publisher.onCourseChanged(new CourseChangedEvent(10L));

		CourseSignal signal = published();
		assertThat(signal.type()).isEqualTo(CourseSignal.Type.VERSION);
		assertThat(signal.courseId()).isEqualTo(10L);
		assertThat(signal.content()).isNull();
	}

	@Test
	@DisplayName("나가기는 그 회원의 REVOKE, 코스 삭제는 전원(memberId null) REVOKE")
	void leftAndDeleted_publishRevoke() throws Exception {
		publisher.onMemberLeft(new CourseMemberLeftEvent(10L, 7L));
		assertThat(published()).isEqualTo(CourseSignal.revoke(10L, 7L));

		publisher.onCourseDeleted(new CourseDeletedEvent(10L));
		ArgumentCaptor<Object> json = ArgumentCaptor.forClass(Object.class);
		then(template).should(org.mockito.Mockito.times(2)).convertAndSend(eq(CourseSignalPublisher.CHANNEL),
			json.capture());
		assertThat(objectMapper.readValue(json.getAllValues().get(1).toString(), CourseSignal.class))
			.isEqualTo(CourseSignal.revoke(10L, null));
	}

	@Test
	@DisplayName("Redis 가 안 되면 로그만 남긴다 — 신호는 없어도 폴링이 메운다")
	void redisFailure_swallowed() {
		willThrow(new RedisConnectionFailureException("down")).given(template)
			.convertAndSend(eq(CourseSignalPublisher.CHANNEL), any());

		assertThatCode(() -> publisher.onCourseChanged(new CourseChangedEvent(10L))).doesNotThrowAnyException();
	}
}

package sweetgirl.croissant.course.batch;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.course.service.CourseReminderPushService;

@ExtendWith(MockitoExtension.class)
class CourseReminderSchedulerTest {

	@Mock
	private CourseReminderPushService courseReminderPushService;

	@InjectMocks
	private CourseReminderScheduler courseReminderScheduler;

	@Test
	@DisplayName("전날 저녁 알림을 보낸다")
	void sends() {
		courseReminderScheduler.tomorrowReminders();

		then(courseReminderPushService).should().sendTomorrowReminders(any(LocalDateTime.class));
	}

	@Test
	@DisplayName("발송이 실패해도 스케줄러는 죽지 않는다")
	void swallowsFailure() {
		given(courseReminderPushService.sendTomorrowReminders(any(LocalDateTime.class)))
			.willThrow(new IllegalStateException("db down"));

		assertThatCode(() -> courseReminderScheduler.tomorrowReminders()).doesNotThrowAnyException();
	}
}

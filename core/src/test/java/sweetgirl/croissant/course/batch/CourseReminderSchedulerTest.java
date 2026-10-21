package sweetgirl.croissant.course.batch;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.course.service.CourseReminderPushService;
import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.service.RewardLaunchService;

@ExtendWith(MockitoExtension.class)
class CourseReminderSchedulerTest {

	@Mock
	private CourseReminderPushService courseReminderPushService;
	@Mock
	private RewardLaunchService rewardLaunchService;

	@InjectMocks
	private CourseReminderScheduler courseReminderScheduler;

	private void givenPushDue(boolean due) {
		given(rewardLaunchService.isPushDue(eq(RewardSettingKey.PUSH_COURSE_TOMORROW), any(LocalDate.class)))
			.willReturn(due);
	}

	@Test
	@DisplayName("보낼 때가 아니면(스위치 꺼짐 · 출시 전) 전날 알림을 보내지 않는다 — 2.0 전 앱은 알림함에서 이 타입을 찜으로 그린다")
	void off() {
		givenPushDue(false);

		courseReminderScheduler.tomorrowReminders();

		then(courseReminderPushService).should(never()).sendTomorrowReminders(any(LocalDateTime.class));
	}

	@Test
	@DisplayName("보낼 때가 됐으면 전날 저녁 알림을 보낸다")
	void sends() {
		givenPushDue(true);

		courseReminderScheduler.tomorrowReminders();

		then(courseReminderPushService).should().sendTomorrowReminders(any(LocalDateTime.class));
	}

	@Test
	@DisplayName("발송이 실패해도 스케줄러는 죽지 않는다")
	void swallowsFailure() {
		givenPushDue(true);
		given(courseReminderPushService.sendTomorrowReminders(any(LocalDateTime.class)))
			.willThrow(new IllegalStateException("db down"));

		assertThatCode(() -> courseReminderScheduler.tomorrowReminders()).doesNotThrowAnyException();
	}
}

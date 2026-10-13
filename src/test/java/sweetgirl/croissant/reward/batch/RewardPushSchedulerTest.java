package sweetgirl.croissant.reward.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.service.RewardPushService;
import sweetgirl.croissant.reward.service.RewardSettings;

@ExtendWith(MockitoExtension.class)
class RewardPushSchedulerTest {

	@Mock
	private RewardPushService rewardPushService;

	@Mock
	private RewardSettings rewardSettings;

	@InjectMocks
	private RewardPushScheduler rewardPushScheduler;

	@Test
	@DisplayName("편지 스위치가 꺼져 있으면 편지 도착 푸시를 보내지 않는다")
	void letterOff() {
		given(rewardSettings.isOn(RewardSettingKey.PUSH_LETTER)).willReturn(false);

		rewardPushScheduler.letterArrivals();

		then(rewardPushService).should(never()).sendLetterArrivals(any(LocalDateTime.class));
	}

	@Test
	@DisplayName("편지 스위치가 켜져 있으면 편지 도착 푸시를 보낸다")
	void letterOn() {
		given(rewardSettings.isOn(RewardSettingKey.PUSH_LETTER)).willReturn(true);

		rewardPushScheduler.letterArrivals();

		then(rewardPushService).should().sendLetterArrivals(any(LocalDateTime.class));
	}

	@Test
	@DisplayName("출석 스위치가 꺼져 있으면 출석 리마인드를 보내지 않는다")
	void attendanceOff() {
		given(rewardSettings.isOn(RewardSettingKey.PUSH_ATTENDANCE)).willReturn(false);

		rewardPushScheduler.attendanceReminders();

		then(rewardPushService).should(never()).sendAttendanceReminders(any(LocalDateTime.class));
	}

	@Test
	@DisplayName("출석 스위치가 켜져 있으면 출석 리마인드를 보낸다")
	void attendanceOn() {
		given(rewardSettings.isOn(RewardSettingKey.PUSH_ATTENDANCE)).willReturn(true);

		rewardPushScheduler.attendanceReminders();

		then(rewardPushService).should().sendAttendanceReminders(any(LocalDateTime.class));
	}
}

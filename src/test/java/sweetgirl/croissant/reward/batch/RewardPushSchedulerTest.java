package sweetgirl.croissant.reward.batch;

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

import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.service.RewardLaunchService;
import sweetgirl.croissant.reward.service.RewardPushService;

@ExtendWith(MockitoExtension.class)
class RewardPushSchedulerTest {

	@Mock
	private RewardPushService rewardPushService;

	@Mock
	private RewardLaunchService rewardLaunchService;

	@InjectMocks
	private RewardPushScheduler rewardPushScheduler;

	@Test
	@DisplayName("편지 보낼 때가 아니면 편지 도착 푸시를 보내지 않는다")
	void letterOff() {
		given(rewardLaunchService.isPushDue(eq(RewardSettingKey.PUSH_LETTER), any(LocalDate.class))).willReturn(false);

		rewardPushScheduler.letterArrivals();

		then(rewardPushService).should(never()).sendLetterArrivals(any(LocalDateTime.class));
	}

	@Test
	@DisplayName("편지 보낼 때가 됐으면 편지 도착 푸시를 보낸다")
	void letterOn() {
		given(rewardLaunchService.isPushDue(eq(RewardSettingKey.PUSH_LETTER), any(LocalDate.class))).willReturn(true);

		rewardPushScheduler.letterArrivals();

		then(rewardPushService).should().sendLetterArrivals(any(LocalDateTime.class));
	}

	@Test
	@DisplayName("출석 보낼 때가 아니면 둘째 날 아침 룰렛 알림도 보내지 않는다")
	void rouletteOff() {
		given(rewardLaunchService.isPushDue(eq(RewardSettingKey.PUSH_ATTENDANCE), any(LocalDate.class)))
			.willReturn(false);

		rewardPushScheduler.secondMorningRoulette();

		then(rewardPushService).should(never()).sendSecondMorningRoulette(any(LocalDateTime.class));
	}

	@Test
	@DisplayName("출석 보낼 때가 됐으면 둘째 날 아침 룰렛 알림을 보낸다")
	void rouletteOn() {
		given(rewardLaunchService.isPushDue(eq(RewardSettingKey.PUSH_ATTENDANCE), any(LocalDate.class)))
			.willReturn(true);

		rewardPushScheduler.secondMorningRoulette();

		then(rewardPushService).should().sendSecondMorningRoulette(any(LocalDateTime.class));
	}

	@Test
	@DisplayName("출석 보낼 때가 아니면 출석 리마인드를 보내지 않는다")
	void attendanceOff() {
		given(rewardLaunchService.isPushDue(eq(RewardSettingKey.PUSH_ATTENDANCE), any(LocalDate.class)))
			.willReturn(false);

		rewardPushScheduler.attendanceReminders();

		then(rewardPushService).should(never()).sendAttendanceReminders(any(LocalDateTime.class));
	}

	@Test
	@DisplayName("출석 보낼 때가 됐으면 출석 리마인드를 보낸다")
	void attendanceOn() {
		given(rewardLaunchService.isPushDue(eq(RewardSettingKey.PUSH_ATTENDANCE), any(LocalDate.class)))
			.willReturn(true);

		rewardPushScheduler.attendanceReminders();

		then(rewardPushService).should().sendAttendanceReminders(any(LocalDateTime.class));
	}

	@Test
	@DisplayName("두 번째 빵로그 스위치가 꺼져 있으면 보내지 않는다")
	void recordNudgeOff() {
		given(rewardLaunchService.isPushDue(eq(RewardSettingKey.PUSH_RECORD_NUDGE), any(LocalDate.class)))
			.willReturn(false);

		rewardPushScheduler.secondRecordNudges();

		then(rewardPushService).should(never()).sendSecondRecordNudges(any(LocalDateTime.class));
	}

	@Test
	@DisplayName("두 번째 빵로그 스위치가 켜져 있으면 보낸다")
	void recordNudgeOn() {
		given(rewardLaunchService.isPushDue(eq(RewardSettingKey.PUSH_RECORD_NUDGE), any(LocalDate.class)))
			.willReturn(true);

		rewardPushScheduler.secondRecordNudges();

		then(rewardPushService).should().sendSecondRecordNudges(any(LocalDateTime.class));
	}
}

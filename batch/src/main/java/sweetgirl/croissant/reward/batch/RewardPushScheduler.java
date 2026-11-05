package sweetgirl.croissant.reward.batch;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.batch.job.BatchJob;
import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.service.CrumbGrantService;
import sweetgirl.croissant.reward.service.RewardLaunchService;
import sweetgirl.croissant.reward.service.RewardPushService;

/**
 * 리워드의 정기 푸시를 제시간에 부른다.
 *
 * <p><b>기본은 꺼져 있다.</b> 실제 사용자 기기로 나가는 알림이라, 2.0 앱이 나가고 받을 화면이 생긴 뒤에
 * 어드민이 스위치({@code push.letter} · {@code push.attendance})를 켜거나, 출시 시각에서
 * {@code push.auto-after-days} 일이 지나면 켜진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RewardPushScheduler {

	private final RewardPushService rewardPushService;

	private final RewardLaunchService rewardLaunchService;

	/** 매일 저녁 8 시 — 어제 출석한 회원의 오늘 도장. */
	@BatchJob("reward-attendance-reminder")
	@SchedulerLock(name = "RewardPushScheduler_attendanceReminders", lockAtMostFor = "PT10M")
	public void attendanceReminders() {
		if (!rewardLaunchService.isPushDue(RewardSettingKey.PUSH_ATTENDANCE, LocalDate.now(CrumbGrantService.ZONE))) {
			return;
		}
		try {
			rewardPushService.sendAttendanceReminders(LocalDateTime.now(CrumbGrantService.ZONE));
		} catch (RuntimeException exception) {
			log.error("[리워드 푸시] 출석 리마인드 실패 — 오늘은 보내지 않는다", exception);
		}
	}

	/** 월요일 오전 10 시 — 이번 주 빵댕이 편지 도착. */
	@BatchJob("reward-letter-arrival")
	@SchedulerLock(name = "RewardPushScheduler_letterArrivals", lockAtMostFor = "PT10M")
	public void letterArrivals() {
		if (!rewardLaunchService.isPushDue(RewardSettingKey.PUSH_LETTER, LocalDate.now(CrumbGrantService.ZONE))) {
			return;
		}
		try {
			rewardPushService.sendLetterArrivals(LocalDateTime.now(CrumbGrantService.ZONE));
		} catch (RuntimeException exception) {
			log.error("[리워드 푸시] 편지 도착 알림 실패 — 이번 주는 보내지 않는다", exception);
		}
	}

	/** 매일 저녁 7 시 — 2 · 3 일 전에 첫 기록을 남기고 두 번째가 없는 회원에게 "두 번째 빵로그". */
	@BatchJob("reward-second-record-nudge")
	@SchedulerLock(name = "RewardPushScheduler_secondRecordNudges", lockAtMostFor = "PT10M")
	public void secondRecordNudges() {
		if (!rewardLaunchService.isPushDue(RewardSettingKey.PUSH_RECORD_NUDGE, LocalDate.now(CrumbGrantService.ZONE))) {
			return;
		}
		try {
			rewardPushService.sendSecondRecordNudges(LocalDateTime.now(CrumbGrantService.ZONE));
		} catch (RuntimeException exception) {
			log.error("[리워드 푸시] 두 번째 빵로그 알림 실패 — 오늘은 보내지 않는다", exception);
		}
	}

	/** 매일 아침 9 시 — 어제 가입하고 아직 출석 안 한 회원에게 룰렛. 출석 스위치를 따른다. */
	@BatchJob("reward-second-morning-roulette")
	@SchedulerLock(name = "RewardPushScheduler_secondMorningRoulette", lockAtMostFor = "PT10M")
	public void secondMorningRoulette() {
		if (!rewardLaunchService.isPushDue(RewardSettingKey.PUSH_ATTENDANCE, LocalDate.now(CrumbGrantService.ZONE))) {
			return;
		}
		try {
			rewardPushService.sendSecondMorningRoulette(LocalDateTime.now(CrumbGrantService.ZONE));
		} catch (RuntimeException exception) {
			log.error("[리워드 푸시] 둘째 날 아침 룰렛 알림 실패 — 오늘은 보내지 않는다", exception);
		}
	}
}

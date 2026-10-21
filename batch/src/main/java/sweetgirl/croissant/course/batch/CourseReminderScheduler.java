package sweetgirl.croissant.course.batch;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.batch.job.BatchJob;
import sweetgirl.croissant.course.service.CourseReminderPushService;
import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.service.RewardLaunchService;

/**
 * 빵투어 전날 저녁 7 시 — 내일 가는 빵투어의 참여자에게. 출석 리마인드(저녁 8 시)와 겹치지 않게 한 시간 앞이다.
 *
 * <p>스위치({@code push.course-tomorrow})를 따른다 — 2.0 전 앱은 알림함에서 이 타입을 '찜'으로 그리므로,
 * 2.0 이 깔릴 때까지 꺼 두고 다른 리워드 푸시와 같은 때에 켠다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourseReminderScheduler {

	static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	private final CourseReminderPushService courseReminderPushService;
	private final RewardLaunchService rewardLaunchService;

	@BatchJob("course-reminder")
	@SchedulerLock(name = "CourseReminderScheduler_tomorrowReminders", lockAtMostFor = "PT10M")
	public void tomorrowReminders() {
		if (!rewardLaunchService.isPushDue(RewardSettingKey.PUSH_COURSE_TOMORROW, LocalDate.now(ZONE))) {
			return;
		}
		try {
			courseReminderPushService.sendTomorrowReminders(LocalDateTime.now(ZONE));
		} catch (RuntimeException exception) {
			log.error("[빵투어 전날 푸시] 실패 — 오늘은 보내지 않는다", exception);
		}
	}
}

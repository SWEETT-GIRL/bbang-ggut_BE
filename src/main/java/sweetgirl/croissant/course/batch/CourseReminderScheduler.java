package sweetgirl.croissant.course.batch;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.course.service.CourseReminderPushService;

/** 빵투어 전날 저녁 7 시 — 내일 가는 빵투어의 참여자에게. 출석 리마인드(저녁 8 시)와 겹치지 않게 한 시간 앞이다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourseReminderScheduler {

	static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	private final CourseReminderPushService courseReminderPushService;

	@Scheduled(cron = "0 0 19 * * *", zone = "Asia/Seoul")
	public void tomorrowReminders() {
		try {
			courseReminderPushService.sendTomorrowReminders(LocalDateTime.now(ZONE));
		} catch (RuntimeException exception) {
			log.error("[빵투어 전날 푸시] 실패 — 오늘은 보내지 않는다", exception);
		}
	}
}

package sweetgirl.croissant.monthlyreport.service;

import java.time.LocalDateTime;
import java.time.YearMonth;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.NotificationChannel;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.alarm.event.PushRequestedEvent;
import sweetgirl.croissant.alarm.service.NotificationRecorder;
import sweetgirl.croissant.member.entity.Member;

/** 발행과 알림함 적재는 한 트랜잭션, 실제 푸시는 기존 AFTER_COMMIT 리스너가 처리한다. */
@Service
@RequiredArgsConstructor
public class MonthlyReportNotificationService {

	private final NotificationRecorder recorder;
	private final ApplicationEventPublisher eventPublisher;

	@Transactional(propagation = Propagation.MANDATORY)
	public void recordPublished(Member member, YearMonth month, LocalDateTime now) {
		String link = "/reports/monthly/" + month;
		Notification notification = Notification.announcement(NotificationType.MONTHLY_REPORT, NotificationChannel.PUSH,
			month.getMonthValue() + "월 빵일지가 도착했어요", month.getMonthValue() + "월의 빵로그과 활동을 확인해 보세요.", link);
		Notification recorded = recorder.recordFor(notification, member, now);
		eventPublisher.publishEvent(PushRequestedEvent.to(recorded.getId(), member.getId()));
	}
}

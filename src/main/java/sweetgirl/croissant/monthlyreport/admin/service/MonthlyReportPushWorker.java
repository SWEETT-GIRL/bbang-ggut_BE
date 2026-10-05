package sweetgirl.croissant.monthlyreport.admin.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.entity.NotificationDelivery;
import sweetgirl.croissant.alarm.enums.DeliveryStatus;
import sweetgirl.croissant.alarm.enums.NotificationChannel;
import sweetgirl.croissant.alarm.repository.NotificationDeliveryRepository;
import sweetgirl.croissant.alarm.repository.NotificationRepository;
import sweetgirl.croissant.alarm.service.PushDispatcher;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportPush;
import sweetgirl.croissant.monthlyreport.admin.repository.MonthlyReportPushRepository;

@Service
@RequiredArgsConstructor
public class MonthlyReportPushWorker {
	private final MonthlyReportPushRepository pushes;
	private final NotificationRepository notifications;
	private final MemberRepository members;
	private final NotificationDeliveryRepository deliveries;
	private final PushDispatcher dispatcher;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void send(Long notificationId) {
		MonthlyReportPush push = pushes.lock(notificationId).orElse(null);
		if (push == null || push.isDone() || push.getAttempts() >= 5
			|| push.getNextAttemptAt().isAfter(Instant.now())) {
			return;
		}
		Member member = members.findById(push.getMemberId()).orElse(null);
		Notification notification = notifications.findById(notificationId).orElse(null);
		if (member == null || member.isDeleted() || notification == null || sent(push)) {
			push.complete();
			return;
		}
		push.begin();
		try {
			dispatcher.dispatch(notification, member, LocalDateTime.now(ZoneId.of("Asia/Seoul")));
			NotificationDelivery delivery = deliveries.findByNotificationIdAndMemberIdAndChannel(notificationId,
				push.getMemberId(), NotificationChannel.PUSH).orElse(null);
			if (delivery == null || delivery.getStatus() == DeliveryStatus.SENT) {
				push.complete();
			} else {
				push.fail();
			}
		} catch (RuntimeException e) {
			push.fail();
		}
	}

	private boolean sent(MonthlyReportPush push) {
		return deliveries.existsByNotificationIdAndMemberIdAndChannelAndStatus(push.getNotificationId(),
			push.getMemberId(), NotificationChannel.PUSH, DeliveryStatus.SENT);
	}
}

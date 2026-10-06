package sweetgirl.croissant.alarm.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.DeliveryStatus;
import sweetgirl.croissant.alarm.repository.NotificationDeliveryRepository;
import sweetgirl.croissant.member.entity.Member;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class NotificationDeliveryCommandService {
	private final NotificationDeliveryRepository deliveryRepository;

	public void recordDelivery(Notification notification, Member member, boolean sent, String failReason,
			LocalDateTime now) {
		deliveryRepository.recordPushResult(notification.getId(), member.getId(),
			sent ? DeliveryStatus.SENT.name() : DeliveryStatus.FAILED.name(), sent ? null : failReason, now);
	}
}

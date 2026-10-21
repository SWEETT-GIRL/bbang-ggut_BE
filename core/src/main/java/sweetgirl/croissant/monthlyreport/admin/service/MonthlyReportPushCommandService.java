package sweetgirl.croissant.monthlyreport.admin.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.entity.Notification;
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
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class MonthlyReportPushCommandService {
	private final MonthlyReportPushRepository pushRepository;
	private final NotificationRepository notificationRepository;
	private final MemberRepository memberRepository;
	private final NotificationDeliveryRepository deliveryRepository;

	public Optional<DeliveryClaim> claimScheduledPush(Long notificationId) {
		Optional<MonthlyReportPush> locked = pushRepository.lock(notificationId);
		if (locked.isEmpty() || !locked.get().isClaimAvailable(Instant.now())) {
			return Optional.empty();
		}
		MonthlyReportPush push = locked.get();
		if (deliveryRepository.existsByNotificationIdAndMemberIdAndChannelAndStatus(notificationId,
			push.getMemberId(), NotificationChannel.PUSH, DeliveryStatus.SENT)) {
			push.complete();
			return Optional.empty();
		}
		if (push.getAttempts() >= 5) {
			push.fail();
			return Optional.empty();
		}
		Optional<Member> member = memberRepository.findById(push.getMemberId()).filter(value -> !value.isDeleted());
		Optional<Notification> notification = notificationRepository.findById(notificationId);
		if (member.isEmpty() || notification.isEmpty()) {
			push.skip("RECIPIENT_UNAVAILABLE");
			return Optional.empty();
		}
		UUID token = push.claimDelivery(Instant.now());
		return Optional.of(new DeliveryClaim(token, notification.get(), member.get()));
	}

	public void completeScheduledPush(Long notificationId, UUID token, PushDispatcher.Result result) {
		pushRepository.lock(notificationId).filter(push -> push.ownsDelivery(token)).ifPresent(push -> {
			switch (result) {
				case SENT -> push.complete();
				case SKIPPED -> push.skip("NO_ELIGIBLE_DELIVERY");
				case FAILED -> push.fail();
			}
		});
	}

	/** Entity는 내부 발송기에만 전달하며 API 응답으로 노출하지 않는다. 필요한 값은 기본 필드다. */
	public record DeliveryClaim(UUID token, Notification notification, Member member) {
	}
}

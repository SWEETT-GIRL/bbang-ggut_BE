package sweetgirl.croissant.alarm.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.entity.NotificationDelivery;
import sweetgirl.croissant.alarm.entity.NotificationRecipient;
import sweetgirl.croissant.alarm.enums.NotificationChannel;
import sweetgirl.croissant.alarm.repository.NotificationDeliveryRepository;
import sweetgirl.croissant.alarm.repository.NotificationRecipientRepository;
import sweetgirl.croissant.alarm.repository.NotificationRepository;
import sweetgirl.croissant.member.entity.Member;

/**
 * 알림을 알림함에 앉히는 일만 한다. 무엇을 알릴지 정하는 것은 부르는 쪽이고, 밖으로 내보내는
 * 것은 {@link PushDispatcher}다.
 *
 * <p>따로 떼어낸 이유는 순서 때문이다. 내용 한 행을 먼저 만들어 id를 받아야 우편함 줄을 꽂을 수
 * 있고, 우편함 줄이 있어야 대장에 인앱 발송을 적을 수 있다. 이 순서를 부르는 쪽마다 다시 쓰면
 * 언젠가 한 군데서 빠진다 — 우편함에는 있는데 대장에 없으면 "인앱조차 안 나갔다"로 읽힌다.
 */
@Component
@RequiredArgsConstructor
public class NotificationRecorder {

	private final NotificationRepository notificationRepository;
	private final NotificationRecipientRepository recipientRepository;
	private final NotificationDeliveryRepository deliveryRepository;

	/**
	 * 한 사람에게 가는 알림. 활동 알림과 제보 결과가 이 길로 온다.
	 *
	 * <p>저장된 알림을 돌려준다 — 부르는 쪽은 곧바로 그 id로 푸시 이벤트를 낸다. 넘긴 인스턴스에
	 * id가 채워지는 것에 기대면 저장 방식이 바뀌는 날 조용히 null이 흘러간다.
	 */
	@Transactional
	public Notification recordFor(Notification notification, Member member, LocalDateTime now) {
		Notification saved = notificationRepository.save(notification);
		recipientRepository.save(NotificationRecipient.of(saved, member, now));
		deliveryRepository.save(
			NotificationDelivery.sent(saved, member, NotificationChannel.IN_APP, now));
		return saved;
	}

	/**
	 * 정해 둔 사람들에게 가는 같은 알림 — 편지 발송처럼. 내용은 한 행이고 우편함·대장만 사람 수만큼 쌓인다.
	 * 전 회원 발송과 달리 많아야 수백 명이라 엔티티로 넣는다.
	 */
	@Transactional
	public Notification recordForMembers(Notification notification, List<Member> members, LocalDateTime now) {
		Notification saved = notificationRepository.save(notification);
		for (Member member : members) {
			recipientRepository.save(NotificationRecipient.of(saved, member, now));
			deliveryRepository.save(
				NotificationDelivery.sent(saved, member, NotificationChannel.IN_APP, now));
		}
		return saved;
	}

	/**
	 * 전 회원에게 가는 알림. 내용은 한 행이고 우편함만 사람 수만큼 늘어난다.
	 *
	 * <p>회원을 전부 엔티티로 올려 saveAll 하면 만 명이면 만 개를 메모리에 얹는다. 넣을 값이
	 * 사람마다 id 하나뿐이라 DB 안에서 처리한다. 대장의 인앱 줄도 같은 이유로 우편함에서 바로 뜬다.
	 */
	@Transactional
	public Broadcast recordForAllMembers(Notification notification, LocalDateTime now) {
		Notification saved = notificationRepository.save(notification);
		int recipients = recipientRepository.insertForAllMembers(saved.getId(), now);
		deliveryRepository.insertInAppForRecipients(saved.getId(), now);
		return new Broadcast(saved, recipients);
	}

	/** 전체 발송의 결과 — 무엇을 보냈고 몇 명의 알림함에 앉았는가. */
	public record Broadcast(Notification notification, int recipients) {
	}
}

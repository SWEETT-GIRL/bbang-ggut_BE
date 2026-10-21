package sweetgirl.croissant.alarm.service;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.config.PushAsyncConfig;
import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.entity.NotificationRecipient;
import sweetgirl.croissant.alarm.enums.NotificationChannel;
import sweetgirl.croissant.alarm.event.PushRequestedEvent;
import sweetgirl.croissant.alarm.repository.NotificationRecipientRepository;
import sweetgirl.croissant.alarm.repository.NotificationRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;

/**
 * 푸시를 실제로 쏘는 자리. 알림을 적재한 트랜잭션이 <b>커밋된 뒤에</b>, <b>다른 스레드</b>에서 돈다.
 *
 * <p>커밋 후인 이유: 적재가 롤백됐는데 푸시만 나가면 앱에는 알림이 왔는데 열어보면 없다.
 * 다른 스레드인 이유: FCM 왕복은 네트워크라, 적재를 부른 요청이 그것을 기다리고 있을 이유가 없다.
 *
 * <p>여기서 나는 예외는 밖으로 나가지 않는다. 커밋은 이미 끝났고, 푸시가 안 나갔다고 되돌릴 것이
 * 없다. 알림함에는 남아 있으므로 사용자가 잃는 것은 "지금 울리는 것"뿐이다.
 */
@Component
@RequiredArgsConstructor
public class PushEventListener {

	/** 공지 팬아웃을 한 번에 다 올리지 않는다. 만 명이면 만 개를 메모리에 얹게 된다. */
	private static final int FANOUT_PAGE_SIZE = 500;

	private static final Logger log = LoggerFactory.getLogger(PushEventListener.class);

	private final NotificationRepository notificationRepository;
	private final NotificationRecipientRepository recipientRepository;
	private final MemberRepository memberRepository;
	private final PushDispatcher pushDispatcher;

	// AFTER_COMMIT 시점에는 붙을 트랜잭션이 이미 끝나 있다. REQUIRES_NEW 로 새로 연다 --
	// 기본 전파를 쓰면 스프링이 아예 기동을 막는다(조용히 트랜잭션 없이 도는 것보다 낫다).
	@Async(PushAsyncConfig.PUSH_EXECUTOR)
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public void handle(PushRequestedEvent event) {
		try {
			Notification notification = notificationRepository.findById(event.notificationId())
				.orElse(null);
			if (notification == null) {
				log.warn("푸시 skip — 알림이 사라졌다 [notificationId={}]", event.notificationId());
				return;
			}
			// 울리지 않기로 하고 저장된 알림. 이벤트를 내지 않는 것이 1차 방어고 여기가 2차다 --
			// 판단 기준을 저장된 값 하나로 두면 부르는 쪽이 늘어도 규칙이 갈라지지 않는다.
			if (notification.getChannel() != NotificationChannel.PUSH) {
				log.info("푸시 skip — 알림함까지만 나가는 알림이다 [notificationId={}, channel={}]",
					notification.getId(), notification.getChannel());
				return;
			}
			if (event.isBroadcast()) {
				broadcast(notification);
			} else {
				sendToOne(notification, event.memberId());
			}
		} catch (Exception e) {
			log.error("푸시 처리 실패 [notificationId={}, memberId={}]",
				event.notificationId(), event.memberId(), e);
		}
	}

	private void sendToOne(Notification notification, Long memberId) {
		Member member = memberRepository.findByIdAndDeletedAtIsNull(memberId).orElse(null);
		if (member == null) {
			log.info("푸시 skip — 받을 사람이 사라졌다 [memberId={}]", memberId);
			return;
		}
		pushDispatcher.dispatch(notification, member, LocalDateTime.now());
	}

	/**
	 * 공지처럼 수신자가 여럿인 알림. 한 사람이 실패해도 나머지는 계속 간다 —
	 * 토큰 하나가 죽었다고 전체 공지가 멈출 이유가 없다.
	 */
	private void broadcast(Notification notification) {
		int page = 0;
		int sent = 0;
		Slice<NotificationRecipient> slice;
		do {
			Pageable pageable = PageRequest.of(page, FANOUT_PAGE_SIZE);
			slice = recipientRepository.findByNotificationId(notification.getId(), pageable);
			for (NotificationRecipient recipient : slice.getContent()) {
				try {
					pushDispatcher.dispatch(notification, recipient.getMember(), LocalDateTime.now());
					sent++;
				} catch (Exception e) {
					log.warn("푸시 실패 — 건너뛴다 [notificationId={}, memberId={}]",
						notification.getId(), recipient.getMember().getId(), e);
				}
			}
			page++;
		} while (slice.hasNext());

		log.info("공지 푸시 완료 [notificationId={}, attempted={}]", notification.getId(), sent);
	}
}

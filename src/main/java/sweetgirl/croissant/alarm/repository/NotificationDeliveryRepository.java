package sweetgirl.croissant.alarm.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.alarm.entity.NotificationDelivery;
import sweetgirl.croissant.alarm.enums.DeliveryStatus;
import sweetgirl.croissant.alarm.enums.NotificationChannel;

/** 발송 대장. 쌓기만 하고 목록 조회에는 쓰이지 않는다. */
public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {

	boolean existsByNotificationIdAndMemberIdAndChannelAndStatus(Long notificationId, Long memberId,
			NotificationChannel channel, DeliveryStatus status);

	/** 재시도 때 같은 줄을 찾아 덮어쓰기 위한 조회. 유니크 제약과 같은 열 조합이다. */
	Optional<NotificationDelivery> findByNotificationIdAndMemberIdAndChannel(Long notificationId,
			Long memberId, NotificationChannel channel);

	/**
	 * 공지처럼 우편함을 벌크로 꽂은 알림의 인앱 발송을 대장에 옮겨 적는다.
	 * 받는 사람 목록을 다시 메모리로 올리지 않으려고 우편함에서 바로 뜬다.
	 */
	@Modifying
	@Query(value = """
			INSERT INTO notification_delivery
				(notification_id, member_id, channel, status, sent_at)
			SELECT r.notification_id, r.member_id, 'IN_APP', 'SENT', :now
			FROM notification_recipient r
			WHERE r.notification_id = :notificationId
			""", nativeQuery = true)
	int insertInAppForRecipients(@Param("notificationId") Long notificationId,
			@Param("now") LocalDateTime now);

	/** 동시 재시도에서도 이미 확인된 성공을 실패 결과로 되돌리지 않는다. */
	@Modifying
	@Query(value = """
			INSERT INTO notification_delivery (notification_id, member_id, channel, status, fail_reason, sent_at)
			VALUES (:notificationId, :memberId, 'PUSH', :status, :failReason, :now)
			ON CONFLICT (notification_id, member_id, channel) DO UPDATE
			SET status = EXCLUDED.status, fail_reason = EXCLUDED.fail_reason, sent_at = EXCLUDED.sent_at
			WHERE notification_delivery.status <> 'SENT'
			""", nativeQuery = true)
	int recordPushResult(@Param("notificationId") Long notificationId, @Param("memberId") Long memberId,
			@Param("status") String status, @Param("failReason") String failReason, @Param("now") LocalDateTime now);

}

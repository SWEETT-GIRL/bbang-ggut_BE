package sweetgirl.croissant.alarm.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.alarm.enums.NotificationType;

/**
 * 푸시 묶음 대기열의 한 줄 — "이 사람에게 이 알림을 울려야 하는데 아직 안 울렸다".
 *
 * <p>같은 (받는 사람, 종류, 보낸 사람) 줄들이 한 묶음이다. 창이 지나면 묶음을 한 번에 울리고 줄을 지운다.
 * 알림함은 건마다 그대로 남으니 여기는 "울림"만 다룬다. 연관 대신 id 만 들고 있다 — 묶음을 세고 지우는
 * 일에 엔티티를 끌어올 이유가 없다.
 */
@Entity
@Table(name = "push_batch_item", uniqueConstraints = @UniqueConstraint(name = "uk_push_batch_item_member_notification", columnNames = {
	"member_id", "notification_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PushBatchItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Column(name = "notification_id", nullable = false)
	private Long notificationId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 40)
	private NotificationType type;

	@Column(name = "actor_id", nullable = false)
	private Long actorId;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	private PushBatchItem(Long memberId, Long notificationId, NotificationType type, Long actorId,
			LocalDateTime createdAt) {
		this.memberId = memberId;
		this.notificationId = notificationId;
		this.type = type;
		this.actorId = actorId;
		this.createdAt = createdAt;
	}

	public static PushBatchItem of(Long memberId, Notification notification, LocalDateTime now) {
		return new PushBatchItem(memberId, notification.getId(), notification.getType(),
			notification.getActor().getId(), now);
	}
}

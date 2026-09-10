package sweetgirl.croissant.alarm.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.member.entity.Member;

/**
 * 한 사람의 알림함에 놓인 한 줄. 목록과 미읽음 배지가 보는 것은 전부 이 테이블이다.
 *
 * <p>읽음이 여기 있는 이유는 당연해 보이지만 짚어둘 값이 있다 — 공지는 {@link Notification}이
 * 한 행뿐이라 읽음을 저쪽에 둘 자리가 없다. 읽음은 사람마다 다른 사실이다.
 *
 * <p>같은 알림이 같은 사람의 알림함에 두 번 꽂히지 않는다 — 유니크 제약은 마이그레이션이 갖는다.
 *
 * <p>수신 설정과 무관하게 항상 만든다. 푸시를 꺼둔 사람도 앱을 열면 알림함에서 볼 수 있어야 한다.
 * "보냈는가"는 {@link NotificationDelivery}가 따로 답한다.
 */
@Entity
@Table(name = "notification_recipient")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationRecipient extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "notification_id", nullable = false)
	private Notification notification;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	/**
	 * 목록 정렬 기준. BaseTimeEntity의 createdAt은 @CreatedDate라 한 번 박히면 안 바뀌는데,
	 * 저장을 껐다 켠 알림은 맨 위로 올라가야 해서 직접 들고 있는 시각이 따로 필요하다.
	 */
	@Column(name = "notified_at", nullable = false)
	private LocalDateTime notifiedAt;

	@Column(name = "read_at")
	private LocalDateTime readAt;

	private NotificationRecipient(Notification notification, Member member, LocalDateTime notifiedAt) {
		this.notification = notification;
		this.member = member;
		this.notifiedAt = notifiedAt;
	}

	public static NotificationRecipient of(Notification notification, Member member,
			LocalDateTime now) {
		return new NotificationRecipient(notification, member, now);
	}

	/**
	 * 같은 행동을 다시 한 경우(저장 해제 후 재저장). 새 줄을 만들지 않고 안 읽음으로 되돌린다 —
	 * 받는 사람 입장에선 방금 일어난 일이라 목록 맨 위에 있어야 한다.
	 */
	public void renew(LocalDateTime now) {
		this.notifiedAt = now;
		this.readAt = null;
	}

	public void markAsRead(LocalDateTime now) {
		if (this.readAt == null) {
			this.readAt = now;
		}
	}

	public boolean isRead() {
		return this.readAt != null;
	}
}

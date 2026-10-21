package sweetgirl.croissant.alarm.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import sweetgirl.croissant.alarm.enums.DeliveryStatus;
import sweetgirl.croissant.alarm.enums.NotificationChannel;
import sweetgirl.croissant.member.entity.Member;

/**
 * 발송 대장 한 줄 — 언제 어느 채널로 나갔고, 눌렸는가.
 *
 * <p>{@link NotificationRecipient}와 겹쳐 보이지만 답하는 질문이 다르다. 우편함은 "지금 알림함에
 * 무엇이 있나"이고 대장은 "어떻게 나갔나"다.
 *
 * <p>눌렸는지는 적지 않는다. 서버는 알 방법이 없고 앱이 되돌려줘야 하는데, 앱은 이미 그것을
 * Amplitude로 찍고 있다. 같은 사실을 두 군데 쌓으면 언젠가 두 숫자가 어긋난다. 인앱만 쌓이고 푸시는 안 나가는 경우가 정상적으로
 * 존재하기 때문에 둘이 갈라져 있어야 한다 — 수신 거부, 토큰 없음, 발송 실패.
 *
 * <p>한 사람의 한 알림에 채널마다 한 줄이다 — 유니크 제약은 마이그레이션이 갖는다.
 *
 * <p>보내지 않기로 한 것은 줄을 만들지 않는다. 우편함에 행이 있는데 대장에 PUSH 줄이 없으면
 * 그것이 곧 "푸시는 보내지 않았다"는 뜻이다.
 *
 * <p>BaseTimeEntity를 쓰지 않는다. 발송 시각이 곧 생성 시각이고, 대장은 고쳐 쓰는 기록이 아니라
 * 사실을 적는 곳이라 수정 시각이 의미를 갖지 않는다.
 */
@Entity
@Table(name = "notification_delivery")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationDelivery {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "notification_id", nullable = false)
	private Notification notification;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private NotificationChannel channel;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private DeliveryStatus status;

	/** FCM이 돌려준 에러코드(UNREGISTERED 등). 사람이 읽을 문장이 아니라 판정에 쓰는 값이다. */
	@Column(name = "fail_reason", length = 200)
	private String failReason;

	@Column(name = "sent_at", nullable = false)
	private LocalDateTime sentAt;

	private NotificationDelivery(Notification notification, Member member,
			NotificationChannel channel, DeliveryStatus status, String failReason,
			LocalDateTime sentAt) {
		this.notification = notification;
		this.member = member;
		this.channel = channel;
		this.status = status;
		this.failReason = failReason;
		this.sentAt = sentAt;
	}

	public static NotificationDelivery sent(Notification notification, Member member,
			NotificationChannel channel, LocalDateTime now) {
		return new NotificationDelivery(notification, member, channel, DeliveryStatus.SENT, null, now);
	}

	public static NotificationDelivery failed(Notification notification, Member member,
			NotificationChannel channel, String failReason, LocalDateTime now) {
		return new NotificationDelivery(notification, member, channel, DeliveryStatus.FAILED,
			failReason, now);
	}

	/**
	 * 재시도 결과를 같은 줄에 덮어쓴다. 시도 이력이 필요해진 적이 없고, 줄이 늘면 "결국 갔나"를
	 * 매번 집계해야 한다.
	 */
	public void retried(DeliveryStatus status, String failReason, LocalDateTime now) {
		this.status = status;
		this.failReason = failReason;
		this.sentAt = now;
	}
}

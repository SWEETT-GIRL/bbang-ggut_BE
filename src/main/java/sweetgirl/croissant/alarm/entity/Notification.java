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
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.alarm.enums.NotificationTargetType;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.member.entity.Member;

/**
 * 알림 한 줄. 종류가 무엇이든 이 테이블에 쌓인다.
 *
 * <p>대상을 외래키로 묶지 않고 (targetType, targetId, targetTitle)로 펼쳐 둔다. 알림마다 대상
 * 도메인이 다른데 전부 관계로 걸면 컬럼이 도메인 수만큼 늘어나고, 목록 한 번에 조인이 그만큼 붙는다.
 * 제목을 그때 값으로 박아두면 나중에 코스 이름이 바뀌어도 "저장할 당시 그 이름"이 남는다.
 *
 * <p>actor만 관계로 두는 이유는 반대다 — 닉네임은 바뀌면 최신으로 보여야 하고, 탈퇴 여부도
 * 조회 시점에 판단해야 한다.
 */
@Entity
@Table(name = "notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "recipient_id", nullable = false)
	private Member recipient;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private NotificationType type;

	/** 행동한 사람. 공지처럼 사람이 한 일이 아닌 알림에는 없다. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "actor_id")
	private Member actor;

	@Enumerated(EnumType.STRING)
	@Column(name = "target_type", length = 20)
	private NotificationTargetType targetType;

	@Column(name = "target_id")
	private Long targetId;

	@Column(name = "target_title", length = 200)
	private String targetTitle;

	/** 서버가 문구를 갖는 알림(공지)용. 활동 알림은 앱이 조립하므로 비어 있다. */
	@Column(length = 200)
	private String title;

	@Column(columnDefinition = "TEXT")
	private String body;

	/** 탭했을 때 갈 앱 내부 경로. 공지가 특정 화면으로 보낼 때 쓴다. */
	@Column(length = 500)
	private String link;

	/**
	 * 목록 정렬 기준. BaseTimeEntity의 createdAt은 @CreatedDate라 한 번 박히면 안 바뀌는데,
	 * 저장을 껐다 켠 알림은 맨 위로 올라가야 해서 직접 들고 있는 시각이 따로 필요하다.
	 */
	@Column(name = "notified_at", nullable = false)
	private LocalDateTime notifiedAt;

	@Column(name = "read_at")
	private LocalDateTime readAt;

	@Builder(access = AccessLevel.PRIVATE)
	private Notification(Member recipient, NotificationType type, Member actor,
			NotificationTargetType targetType, Long targetId, String targetTitle,
			String title, String body, String link, LocalDateTime notifiedAt) {
		this.recipient = recipient;
		this.type = type;
		this.actor = actor;
		this.targetType = targetType;
		this.targetId = targetId;
		this.targetTitle = targetTitle;
		this.title = title;
		this.body = body;
		this.link = link;
		this.notifiedAt = notifiedAt;
	}

	/** 사람이 한 일 — 저장·좋아요·초대. 문구는 앱이 조립한다. */
	public static Notification activity(Member recipient, NotificationType type, Member actor,
			NotificationTargetType targetType, Long targetId, String targetTitle, LocalDateTime now) {
		return Notification.builder()
			.recipient(recipient)
			.type(type)
			.actor(actor)
			.targetType(targetType)
			.targetId(targetId)
			.targetTitle(targetTitle)
			.notifiedAt(now)
			.build();
	}

	/** 운영 공지. 문구를 서버가 들고 있어 앱 배포 없이 고칠 수 있다. */
	public static Notification notice(Member recipient, String title, String body, String link,
			LocalDateTime now) {
		return Notification.builder()
			.recipient(recipient)
			.type(NotificationType.NOTICE)
			.title(title)
			.body(body)
			.link(link)
			.notifiedAt(now)
			.build();
	}

	/**
	 * 같은 행동을 다시 한 경우(저장 해제 후 재저장). 새 줄을 만들지 않고 안 읽음으로 되돌린다 —
	 * 받는 사람 입장에선 방금 일어난 일이라 목록 맨 위에 있어야 한다.
	 */
	public void renew(LocalDateTime now, String targetTitle) {
		this.notifiedAt = now;
		this.readAt = null;
		if (targetTitle != null) {
			this.targetTitle = targetTitle;
		}
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

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
import sweetgirl.croissant.alarm.enums.ReportKind;
import sweetgirl.croissant.alarm.enums.ReportNotificationAction;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.entity.Store;

@Entity
@Table(name = "report_notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportNotification {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "store_id")
	private Store store;

	@Column(name = "report_id", nullable = false)
	private Long reportId;

	@Column(name = "moderation_action_id")
	private Long moderationActionId;

	@Enumerated(EnumType.STRING)
	@Column(name = "report_kind", nullable = false, length = 30)
	private ReportKind reportKind;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ReportNotificationAction action;

	@Column(nullable = false, length = 100)
	private String title;

	@Column(nullable = false, length = 300)
	private String message;

	@Column
	private LocalDateTime readAt;

	@Column(nullable = false, updatable = false)
	private LocalDateTime sentAt;

	@Builder
	public ReportNotification(Member member, Store store, Long reportId, ReportKind reportKind,
			Long moderationActionId, ReportNotificationAction action, String title, String message) {
		this.member = member;
		this.store = store;
		this.reportId = reportId;
		this.moderationActionId = moderationActionId;
		this.reportKind = reportKind;
		this.action = action;
		this.title = title;
		this.message = message;
		this.sentAt = LocalDateTime.now();
	}

	public void markAsRead() {
		this.readAt = LocalDateTime.now();
	}
}

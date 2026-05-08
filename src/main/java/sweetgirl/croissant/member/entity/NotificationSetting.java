package sweetgirl.croissant.member.entity;

import java.time.LocalTime;

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
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;

@Entity
@Table(name = "notification_setting")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationSetting extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@Column(nullable = false)
	private boolean isNotificationEnabled;

	@Column(nullable = false)
	private float notificationRadius;

	@Column(nullable = false)
	private LocalTime startTime;

	@Column(nullable = false)
	private LocalTime endTime;

	@Column(nullable = false, length = 50)
	private String daysOfWeek;

	@Column(nullable = false)
	private boolean isMarketingEnabled;

	@Column(nullable = false)
	private boolean isDndEnabled;

	@Builder
	public NotificationSetting(Member member, boolean isNotificationEnabled, float notificationRadius,
			LocalTime startTime, LocalTime endTime, String daysOfWeek,
			boolean isMarketingEnabled, boolean isDndEnabled) {
		this.member = member;
		this.isNotificationEnabled = isNotificationEnabled;
		this.notificationRadius = notificationRadius;
		this.startTime = startTime;
		this.endTime = endTime;
		this.daysOfWeek = daysOfWeek;
		this.isMarketingEnabled = isMarketingEnabled;
		this.isDndEnabled = isDndEnabled;
	}
}

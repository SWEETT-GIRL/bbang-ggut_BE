package sweetgirl.croissant.member.entity;

import java.time.LocalTime;

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
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.common.enums.NotificationRadius;

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

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private NotificationRadius notificationRadius;

	@Column(nullable = false)
	private LocalTime startTime;

	@Column(nullable = false)
	private LocalTime endTime;

	@Column(nullable = false, length = 50)
	private String daysOfWeek;

	@Column(nullable = false)
	private boolean isMarketingEnabled;

	@Column(nullable = false, columnDefinition = "boolean not null default false")
	private boolean isTimeRestricted;

	public void setDaysOfWeekList(String dayList) {
		this.daysOfWeek = dayList;
	}

	@Builder
	public NotificationSetting(Member member, boolean isNotificationEnabled, NotificationRadius notificationRadius,
			LocalTime startTime, LocalTime endTime, String daysOfWeek,
			boolean isMarketingEnabled, boolean isTimeRestricted) {
		if (isTimeRestricted && (startTime == null || endTime == null)) {
			throw new IllegalArgumentException("isTimeRestricted=true 이면 startTime과 endTime이 필요합니다.");
		}
		this.member = member;
		this.isNotificationEnabled = isNotificationEnabled;
		this.notificationRadius = notificationRadius != null ? notificationRadius : NotificationRadius.RADIUS_500;
		this.startTime = startTime;
		this.endTime = endTime;
		this.daysOfWeek = daysOfWeek;
		this.isMarketingEnabled = isMarketingEnabled;
		this.isTimeRestricted = isTimeRestricted;
	}
}

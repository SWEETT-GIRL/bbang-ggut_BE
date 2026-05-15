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
import sweetgirl.croissant.member.enums.NotificationRadius;

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

	@Column(nullable = false, length = 255)
	private String daysOfWeek;

	@Column(nullable = false)
	private boolean isMarketingEnabled;

	public void setDaysOfWeekList(String dayList) {
		this.daysOfWeek = dayList;
	}

	// 빵알람 수신 여부를 현재 값의 반대로 토글
	public void toggleNotificationEnabled() {
		this.isNotificationEnabled = !this.isNotificationEnabled; // true → false, false → true
	}

	// 마케팅 알람 수신 여부를 현재 값의 반대로 토글
	public void toggleMarketingEnabled() {
		this.isMarketingEnabled = !this.isMarketingEnabled; // true → false, false → true
	}

	// 알림 반경을 새 값으로 업데이트 (dirty checking으로 자동 저장)
	public void updateRadius(NotificationRadius radius) {
		this.notificationRadius = radius;
	}

	// 알림 시간대를 새 값으로 업데이트 (dirty checking으로 자동 저장)
	public void updateTime(LocalTime startTime, LocalTime endTime) {
		this.startTime = startTime;
		this.endTime = endTime;
	}

	@Builder
	public NotificationSetting(Member member, boolean isNotificationEnabled, NotificationRadius notificationRadius,
			LocalTime startTime, LocalTime endTime, String daysOfWeek,
			boolean isMarketingEnabled) {
		this.member = member;
		this.isNotificationEnabled = isNotificationEnabled;
		this.notificationRadius = notificationRadius != null ? notificationRadius : NotificationRadius.R500;
		this.startTime = startTime;
		this.endTime = endTime;
		this.daysOfWeek = daysOfWeek;
		this.isMarketingEnabled = isMarketingEnabled;
	}
}

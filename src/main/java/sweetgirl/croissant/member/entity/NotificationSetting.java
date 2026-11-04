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
import sweetgirl.croissant.alarm.enums.NotificationType;
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

	// 팔로우 · 커뮤니티 종류별 스위치(기본 켜짐). 광고성이 아니라 야간 동의 대상이 아니다.
	@Column(nullable = false)
	private boolean followEnabled = true;

	@Column(nullable = false)
	private boolean patEnabled = true;

	@Column(nullable = false)
	private boolean likeEnabled = true;

	@Column(nullable = false)
	private boolean commentEnabled = true;

	@Column(nullable = false)
	private boolean followingRecordEnabled = true;

	public void setDaysOfWeekList(String dayList) {
		this.daysOfWeek = dayList;
	}

	// 빵알람 수신 여부를 현재 값의 반대로 토글
	/** 바뀌는 키만 보낸다(null 은 그대로). */
	public void updateCommunitySwitches(Boolean follow, Boolean pat, Boolean like, Boolean comment,
			Boolean followingRecord) {
		if (follow != null) {
			this.followEnabled = follow;
		}
		if (pat != null) {
			this.patEnabled = pat;
		}
		if (like != null) {
			this.likeEnabled = like;
		}
		if (comment != null) {
			this.commentEnabled = comment;
		}
		if (followingRecord != null) {
			this.followingRecordEnabled = followingRecord;
		}
	}

	/** 이 종류의 커뮤니티 알림을 받겠다고 했나. 알림함엔 스위치와 무관하게 남고 푸시만 가른다. */
	public boolean allowsCommunity(NotificationType type) {
		return switch (type) {
			case FOLLOW, INVITE_MUTUAL, FOLLOW_REQUEST, FOLLOW_ACCEPTED -> followEnabled;
			case PAT -> patEnabled;
			case RECORD_LIKE -> likeEnabled;
			case RECORD_COMMENT, COMMENT_MENTION, COMMENT_REPLY, COMPANION_TAG -> commentEnabled;
			case FOLLOWING_NEW_RECORD -> followingRecordEnabled;
			default -> true;
		};
	}

	public void toggleNotificationEnabled() {
		this.isNotificationEnabled = !this.isNotificationEnabled; // true → false, false → true
	}

	// 마케팅 알람 수신 여부를 현재 값의 반대로 토글
	public void toggleMarketingEnabled() {
		this.isMarketingEnabled = !this.isMarketingEnabled; // true → false, false → true
	}

	// 마케팅 수신 동의(Member.agreeToMarketing)에 맞춰 설정을 맞춘다 — 발송은 이 값을 본다
	public void updateMarketingEnabled(boolean isMarketingEnabled) {
		this.isMarketingEnabled = isMarketingEnabled;
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

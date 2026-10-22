package sweetgirl.croissant.alarm.dto.response;

import sweetgirl.croissant.member.entity.NotificationSetting;

/** 설정 › 알림의 팔로우 · 톡 · 좋아요 · 댓글 · 팔로잉 소식 스위치(피그마 05-C1). */
public record CommunityNotificationSettingResponse(
		boolean follow,
		boolean pat,
		boolean like,
		boolean comment,
		boolean followingRecord) {

	public static CommunityNotificationSettingResponse from(NotificationSetting setting) {
		return new CommunityNotificationSettingResponse(setting.isFollowEnabled(), setting.isPatEnabled(),
			setting.isLikeEnabled(), setting.isCommentEnabled(), setting.isFollowingRecordEnabled());
	}
}

package sweetgirl.croissant.alarm.dto.request;

/** 바뀌는 키만 보낸다. null 은 그대로 둔다. */
public record CommunityNotificationSettingUpdateRequest(
		Boolean follow,
		Boolean pat,
		Boolean like,
		Boolean comment,
		Boolean followingRecord) {
}

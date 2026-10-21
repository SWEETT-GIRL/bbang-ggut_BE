package sweetgirl.croissant.reward.enums;

/** 내 코드를 넣은 친구 한 명의 초대 상태. */
public enum InviteFriendStatus {
	/** 코드를 넣었고 기한 안이며 아직 첫 빵을 기록하지 않았다 */
	WAITING,
	/** 첫 빵을 기록해 초대가 성사됐다 */
	REWARDED,
	/** 첫 빵 없이 기한이 지났다 — 보상이 없다 */
	EXPIRED
}

package sweetgirl.croissant.alarm.enums;

/** 알림을 탭했을 때 갈 대상의 종류. 갈 곳이 없는 알림에는 없다. */
public enum NotificationTargetType {
	COURSE,
	RECORD,
	STORE,
	/** 문의 스레드 */
	LETTER,
	/** 사람(프로필) */
	MEMBER,
	/** 빵로그 댓글 */
	COMMENT
}

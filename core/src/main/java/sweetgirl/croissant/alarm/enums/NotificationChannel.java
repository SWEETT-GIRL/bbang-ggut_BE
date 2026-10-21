package sweetgirl.croissant.alarm.enums;

/**
 * 알림이 나간 길. {@code notification_delivery} 한 줄이 곧 "이 채널로 이렇게 됐다"는 기록이다.
 *
 * <p>인앱이 채널로 들어와 있는 것이 어색해 보일 수 있다 — 우편함({@code notification_recipient})
 * 행이 이미 있는데 대장에 또 적기 때문이다. 두 테이블이 답하는 질문이 다르다. 우편함은 "지금
 * 알림함에 무엇이 있나"이고, 대장은 "언제 어느 채널로 나갔고 눌렸나"다. 채널이 늘어날수록
 * 발송 기록을 한곳에서 보는 값이 커진다.
 */
public enum NotificationChannel {
	/** FCM 푸시. 수신 동의와 토큰이 있어야 나간다 */
	PUSH,
	/** 인앱 알림함. 수신 설정과 무관하게 항상 쌓인다 */
	IN_APP
}

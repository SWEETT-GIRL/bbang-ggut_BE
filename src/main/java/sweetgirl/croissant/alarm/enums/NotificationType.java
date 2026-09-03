package sweetgirl.croissant.alarm.enums;

/**
 * 알림 종류. 앱이 이 값으로 한 줄의 생김새와 탭했을 때 갈 곳을 정한다.
 *
 * <p>문구를 누가 갖느냐가 갈린다 — 활동 알림(사람이 한 일)은 앱이 조립하고, 공지는 서버가
 * title·body를 그대로 내려준다. 공지 문구를 서버가 가지면 앱 배포 없이 고칠 수 있다.
 */
public enum NotificationType {
	/** 누가 내 빵투어를 저장했다 */
	COURSE_SAVED,
	/** 운영 공지 — 새 버전 소식 등. 서버가 문구를 갖는다 */
	NOTICE,
	/** 아직 기능이 없다. 좋아요가 저장과 갈라지면 그때 쓴다 */
	COURSE_LIKED,
	/** 아직 기능이 없다. 빵투어에 누군가를 부르게 되면 그때 쓴다 */
	INVITE
}

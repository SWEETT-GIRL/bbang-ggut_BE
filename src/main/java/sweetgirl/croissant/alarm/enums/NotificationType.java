package sweetgirl.croissant.alarm.enums;

/**
 * 알림 종류. 앱이 이 값으로 한 줄의 생김새와 탭했을 때 갈 곳을 정한다.
 *
 * <p>문구를 누가 갖느냐가 갈린다 — 활동 알림(사람이 한 일)은 앱이 actor·target으로 조립하고,
 * 나머지는 서버가 title·body를 그대로 내려준다. 서버가 가지면 앱 배포 없이 문구를 고칠 수 있다.
 *
 * <p>{@code pushType}은 푸시 data의 {@code type} 키로 나가는 값이다. enum 이름과 대체로 같지만
 * 기여 제보 결과만 다르다 — 앱은 그것을 {@code REPORT_RESULT}로 알고 있고, 스토어에 깔린 앱을
 * 고칠 수 없으니 서버가 맞춘다. 앱과의 약속을 한곳에 모아두려고 여기 둔다.
 */
public enum NotificationType {

	// ── 활동 알림: 앱이 문구를 조립한다 ──────────────────────────────────────

	/** 누가 내 빵투어를 저장했다 */
	COURSE_SAVED("COURSE_SAVED"),
	/** 아직 기능이 없다. 좋아요가 저장과 갈라지면 그때 쓴다 */
	COURSE_LIKED("COURSE_LIKED"),
	/** 아직 기능이 없다. 빵투어에 누군가를 부르게 되면 그때 쓴다 */
	INVITE("INVITE"),

	// ── 서버가 문구를 갖는 알림 ──────────────────────────────────────────────

	/** 운영 공지 — 새 버전 소식 등 */
	NOTICE("NOTICE"),
	/** 월간 빵일지 발행 알림 */
	MONTHLY_REPORT("MONTHLY_REPORT"),
	/** 광고성 알림. 마케팅 수신 동의가 따로 있어야 나간다 */
	AD("AD"),
	/** 내가 올린 기여 제보(메뉴 추가·가게 추가·가게 정보)의 승인·반려 결과 */
	CONTRIBUTION_RESULT("REPORT_RESULT"),
	/** 내가 신고한 건의 처리 결과 */
	ABUSE_REPORT_RESULT("ABUSE_REPORT_RESULT"),
	/**
	 * 내가 제재당한 알림. 같은 목록에 있되 타입을 따로 두는 이유는 앱이 다르게 그려야 하기
	 * 때문이다 — 이의제기 화면으로 가는 길이 붙어 있고, 흘려보내도 되는 알림이 아니다.
	 */
	MODERATION_ACTION("MODERATION_ACTION"),
	/** 내 문의에 답변이 달렸다 — 어드민이 편지함에서 답장했다 */
	INQUIRY_REPLY("INQUIRY_REPLY"),
	/** 빵별일지 팀이 먼저 편지를 보냈다(설문·안내). 받은 사람에게만 간다 */
	LETTER("LETTER");

	private final String pushType;

	NotificationType(String pushType) {
		this.pushType = pushType;
	}

	/** 푸시 data의 {@code type} 키로 나가는 값. */
	public String pushType() {
		return this.pushType;
	}

	/** 마케팅 수신 동의를 따로 받아야 나가는 알림인가. */
	public boolean requiresMarketingConsent() {
		return this == AD;
	}
}

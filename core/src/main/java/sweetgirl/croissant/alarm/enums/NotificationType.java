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
	/** OO님이 빵투어에 초대했어요 — 앱 안 일행 초대(2026-10-22). 받는 사람이 [수락]/[거절]한다 */
	INVITE("INVITE"),
	// ── 팔로우 · 커뮤니티 활동 알림(2026-10-22): 서버가 문구도 같이 둔다 — 구버전 앱은 모르는 타입을 title · body 로 그린다 ──
	/** OO님이 회원님을 팔로우해요 */
	FOLLOW("FOLLOW"),
	/** OO님이 톡을 쳤어요(P6) */
	PAT("PAT"),
	/** OO님이 회원님의 빵로그를 좋아해요 — 하루 상한 5 */
	RECORD_LIKE("RECORD_LIKE"),
	/** OO님이 댓글을 남겼어요: … — 상한 밖, 하나씩 */
	RECORD_COMMENT("RECORD_COMMENT"),
	/** OO님이 회원님을 언급했어요 — 상한 밖, 하나씩 */
	COMMENT_MENTION("COMMENT_MENTION"),
	/** OO님과 OO에 같이 갔어요(P6) */
	COMPANION_TAG("COMPANION_TAG"),
	/** 팔로잉이 새 빵로그를 올렸어요 — 하루 상한 5 */
	FOLLOWING_NEW_RECORD("FOLLOWING_NEW_RECORD"),
	/** OO님과 서로 팔로우해요(초대 코드 · 일행 합류) */
	INVITE_MUTUAL("INVITE_MUTUAL"),
	/** OO님이 팔로우를 요청했어요 — 비공개 계정에 팔로우를 누르면(2026-11-04). 받는 사람이 요청 목록에서 수락 · 거절한다 */
	FOLLOW_REQUEST("FOLLOW_REQUEST"),
	/** OO님이 팔로우 요청을 수락했어요 — 요청한 사람에게 */
	FOLLOW_ACCEPTED("FOLLOW_ACCEPTED"),
	/** OO님이 회원님의 댓글에 답글을 남겼어요 — 부모 댓글 작성자에게, 하나씩 */
	COMMENT_REPLY("COMMENT_REPLY"),

	// ── 서버가 문구를 갖는 알림 ──────────────────────────────────────────────

	/** 운영 공지 — 새 버전 소식 등 */
	NOTICE("NOTICE"),
	/** 월간 빵일지 발행 알림 */
	MONTHLY_REPORT("MONTHLY_REPORT"),
	/** 내일 가는 빵투어가 있다 — 전날 저녁에 방장과 일행 모두에게. 서비스 알림이다 */
	COURSE_TOMORROW("COURSE_TOMORROW"),
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
	LETTER("LETTER"),
	/** 이번 주 빵댕이 편지가 도착했다(리워드). 광고가 아니라 서비스 알림이다 — 마케팅 동의를 보지 않는다 */
	REWARD_LETTER("REWARD_LETTER"),
	/** 오늘 출석 도장을 아직 안 찍었다(리워드). 어제 출석한 회원에게만 간다. 서비스 알림이다 */
	REWARD_ATTENDANCE("REWARD_ATTENDANCE"),
	/** 어제 가입하고 아직 출석 안 한 회원에게 아침에 친구가 배고프다고 룰렛을 부른다(리워드). 서비스 알림이다 */
	REWARD_ROULETTE("REWARD_ROULETTE"),
	/** 기록이 한 건뿐인 회원에게 2 · 3 일 뒤 저녁에 "두 번째 빵로그" 를 권한다(리워드). 서비스 알림이다 */
	RECORD_NUDGE("RECORD_NUDGE");

	private final String pushType;

	NotificationType(String pushType) {
		this.pushType = pushType;
	}

	/** 푸시 data의 {@code type} 키로 나가는 값. */
	public String pushType() {
		return this.pushType;
	}

	/**
	 * 같은 사람이 같은 사람에게 짧은 시간에 여러 번 만들 수 있는 종류 — 팔로잉 새 빵로그 · 좋아요. 이건 바로 울리지 않고
	 * 창(10분) 동안 모았다가 "N개" 로 한 번 울린다. 톡은 이미 하루 1번이고 댓글 · 멘션 · 팔로우는 하나씩 다 간다.
	 */
	public boolean isBatched() {
		return this == RECORD_LIKE || this == FOLLOWING_NEW_RECORD;
	}

	/** 사람 사이 활동 알림인가 — 알림함에서 같은 날 · 같은 대상끼리 묶고, 종류별 스위치를 본다. */
	public boolean isCommunityActivity() {
		return this == FOLLOW || this == PAT || this == RECORD_LIKE || this == RECORD_COMMENT
			|| this == COMMENT_MENTION || this == COMPANION_TAG || this == FOLLOWING_NEW_RECORD
			|| this == INVITE_MUTUAL || this == FOLLOW_REQUEST || this == FOLLOW_ACCEPTED || this == COMMENT_REPLY;
	}

	/** 마케팅 수신 동의를 따로 받아야 나가는 알림인가. */
	public boolean requiresMarketingConsent() {
		return this == AD;
	}
}

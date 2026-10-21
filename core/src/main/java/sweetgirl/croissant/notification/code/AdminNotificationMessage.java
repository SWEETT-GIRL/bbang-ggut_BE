package sweetgirl.croissant.notification.code;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 운영자(Admin) 슬랙 알림의 메시지 상수 — 채널 색상, 제목/설명 구성 요소.
 *
 * <p>제목과 설명은 (이모지, 주제)로 조합한다.
 * 제목 = {@code "{이모지} {주제} 접수"}, 설명 = {@code "Admin Web에서 확인이 필요한 {주제}가 접수되었습니다."}
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AdminNotificationMessage {

	// 채널 색상
	public static final int REPORT_COLOR = 5763719;
	public static final int ABUSE_COLOR = 15158332;
	public static final int INQUIRY_COLOR = 3447003;
	public static final int LETTER_COLOR = 15277667;

	// 헤더 이모지
	public static final String STORE_EMOJI = "🏪";
	public static final String MENU_EMOJI = "🥐";
	public static final String ABUSE_EMOJI = "🚨";
	public static final String INQUIRY_EMOJI = "💬";
	public static final String LETTER_EMOJI = "💌";

	// 주제 (제목·설명 공통 어구)
	public static final String STORE_ADD_SUBJECT = "새 가게 제보";
	public static final String STORE_UPDATE_SUBJECT = "가게 수정 제보";
	public static final String MENU_ADD_SUBJECT = "새 메뉴 제보";
	public static final String MENU_UPDATE_SUBJECT = "메뉴 수정 제보";
	public static final String REVIEW_ABUSE_SUBJECT = "리뷰 신고";
	public static final String PROFILE_ABUSE_SUBJECT = "프로필 신고";
	public static final String INQUIRY_SUBJECT = "문의";
	public static final String LETTER_SUBJECT = "새 편지";

	private static final String TITLE_FORMAT = "%s %s 접수";
	private static final String DESCRIPTION_FORMAT = "Admin Web에서 확인이 필요한 %s가 접수되었습니다.";

	public static String title(String emoji, String subject) {
		return String.format(TITLE_FORMAT, emoji, subject);
	}

	public static String description(String subject) {
		return String.format(DESCRIPTION_FORMAT, subject);
	}
}

package sweetgirl.croissant.reward.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 부스러기가 왜 움직였는가. 원장({@code crumb_ledger.reason})에 이름 그대로 남는다.
 *
 * <p>값을 지우거나 이름을 바꾸지 않는다 — 원장의 옛 행과 앱의 받은 내역이 이 이름을 읽는다.
 */
@Getter
@RequiredArgsConstructor
public enum CrumbReason {

	WELCOME("환영 선물"),
	ATTENDANCE("출석"),
	FEED("밥 룰렛"),
	RECORD("빵 기록"),
	RECORD_PHOTO("기록에 사진"),
	RECORD_CHAIN("연속 기록 2배"),
	RECORD_SECOND_WEEK("첫 주 두 번째 기록"),
	SAVE_STORE("빵집 담기"),
	LETTER("빵댕이 편지"),
	AD("광고 보기"),
	INVITE_INVITER("친구 초대(초대한 사람)"),
	INVITE_INVITEE("친구 초대(초대받은 사람)"),
	TOUR_VISIT("빵투어 가게 다녀옴"),
	TOUR_COMPLETE("빵투어 완주"),
	TOUR_PHOTO_COMPLETE("빵투어 사진 완주"),
	RANKING("빵순이 랭킹 주간 보상"),
	CARRY_OVER("1.x 사용자 이관 선물"),
	SHOP_PURCHASE("상점 구매"),
	WAKE_FRIEND("친구 깨우기"),
	ADMIN_GRANT("어드민 지급"),
	ADMIN_REVOKE("어드민 회수");

	private final String description;
}

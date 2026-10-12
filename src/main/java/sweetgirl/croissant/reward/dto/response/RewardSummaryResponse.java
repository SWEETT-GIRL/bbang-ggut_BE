package sweetgirl.croissant.reward.dto.response;

import java.util.List;

/**
 * 홈이 한 번에 읽는 리워드 요약.
 *
 * @param crumbs         지금 잔액
 * @param gainedToday    오늘(한국 날짜) 받은 양. 쓴 것은 빼지 않는다
 * @param carryOver      <b>이 호출에서 방금</b> 1.x 이관 선물이 들어갔을 때만 있다. 앱이 안내 화면을 한 번 띄운다
 * @param pendingRewards 앱이 아직 연출하지 못한 보상. 연출을 끝내면 ack 로 지운다
 */
public record RewardSummaryResponse(
		int crumbs,
		long gainedToday,
		RewardRulesResponse rules,
		CarryOver carryOver,
		Welcome welcome,
		List<RewardItemResponse> pendingRewards) {

	/** 게스트는 보상이 없다. 홈이 매번 부르는 조회라 403 대신 빈 요약으로 답한다. */
	public static RewardSummaryResponse guest(RewardRulesResponse rules) {
		return new RewardSummaryResponse(0, 0, rules, null, new Welcome(false), List.of());
	}

	public record CarryOver(boolean granted, int crumbs, int recordCount) {
	}

	/** @param available 환영 선물을 받을 수 있는가. true 일 때 앱이 받기 API 를 부른다 */
	public record Welcome(boolean available) {
	}
}

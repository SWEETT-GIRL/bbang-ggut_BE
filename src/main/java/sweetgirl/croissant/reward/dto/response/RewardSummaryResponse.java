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
		List<RewardItemResponse> pendingRewards,
		Attendance attendance,
		Feed feed) {

	/** 게스트는 보상이 없다. 홈이 매번 부르는 조회라 403 대신 빈 요약으로 답한다. */
	public static RewardSummaryResponse guest(RewardRulesResponse rules, Attendance attendance, Feed feed) {
		return new RewardSummaryResponse(0, 0, rules, null, new Welcome(false), List.of(), attendance, feed);
	}

	/**
	 * 출석 7일판의 지금 모습.
	 *
	 * @param doneToday      오늘 출석했는가
	 * @param stampedDays    판에 찍힌 칸 수(0~7)
	 * @param nextBoardDay   다음에 찍힐 칸. 오늘 안 했으면 오늘 찍을 칸, 했으면 내일 찍을 칸
	 * @param todayReward    오늘 칸의 보상(했으면 받은 칸, 안 했으면 받을 칸)
	 * @param tomorrowReward 내일도 이어서 출석하면 받을 양
	 */
	public record Attendance(
			boolean doneToday,
			int stampedDays,
			int nextBoardDay,
			int todayReward,
			int tomorrowReward) {
	}

	/**
	 * 밥 룰렛.
	 *
	 * @param wedges 칸별 보상. 시계 방향이고 12 시가 0 번이다
	 */
	public record Feed(boolean doneToday, List<Integer> wedges) {
	}

	public record CarryOver(boolean granted, int crumbs, int recordCount) {
	}

	/** @param available 환영 선물을 받을 수 있는가. true 일 때 앱이 받기 API 를 부른다 */
	public record Welcome(boolean available) {
	}
}

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
		Feed feed,
		Letter letter,
		Ad ad,
		RankingReward rankingReward,
		/** 홈의 할 일 카드. 지금 할 만한 것이 없거나 게스트면 null */
		HomeCardResponse homeCard,
		RecordReward record) {

	/** 게스트는 보상이 없다. 홈이 매번 부르는 조회라 403 대신 빈 요약으로 답한다. */
	public static RewardSummaryResponse guest(RewardRulesResponse rules, Attendance attendance, Feed feed) {
		return new RewardSummaryResponse(0, 0, rules, null, new Welcome(false), List.of(), attendance, feed,
			new Letter(0, null), new Ad(0), null, null, new RecordReward(0, 0));
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
	 * 밥 룰렛. 하루 한 판은 무료고, 그 뒤는 광고를 끝까지 보면 한 판 더다.
	 *
	 * @param doneToday      오늘 무료 판을 돌렸는가
	 * @param wedges         칸별 보상. 시계 방향이고 12 시가 0 번이다
	 * @param extraLeftToday 오늘 광고를 보고 더 돌릴 수 있는 남은 횟수(0 ~ rules.feedAdDailyCap). 자정(한국)에 상한으로 돌아온다
	 * @param extraReady     광고를 끝까지 봐서 한 판이 열려 있다. 룰렛을 돌리면 false 다
	 */
	public record Feed(boolean doneToday, List<Integer> wedges, int extraLeftToday, boolean extraReady) {
	}

	/**
	 * 빵댕이 편지.
	 *
	 * @param waitingCount 지금 보이는데 아직 안 연 편지 수
	 * @param latestId     그중 가장 최근 편지. 없으면 null
	 */
	public record Letter(int waitingCount, Long latestId) {
	}

	/**
	 * 받을 수 있는 랭킹 주간 보상(가장 최근 주). 없으면 요약의 이 칸이 null 이다.
	 *
	 * @param pendingId 받기를 누를 때 보내는 id
	 * @param crown     왕관이 딸린 보상인가(1 위)
	 */
	public record RankingReward(Long pendingId, int rank, int crumbs, boolean crown) {
	}

	/**
	 * 오늘의 기록 보상. 빵투어의 가벼운 기록도 여기에 센다.
	 *
	 * @param rewardedToday 오늘 기록 보상을 받은 횟수
	 * @param leftToday     오늘 기록 보상을 더 받을 수 있는 횟수. 0 이면 기록해도 레벨 칸만 오른다
	 */
	public record RecordReward(int rewardedToday, int leftToday) {
	}

	/** @param leftToday 오늘 광고로 더 받을 수 있는 횟수 */
	public record Ad(int leftToday) {
	}

	public record CarryOver(boolean granted, int crumbs, int recordCount) {
	}

	/** @param available 환영 선물을 받을 수 있는가. true 일 때 앱이 받기 API 를 부른다 */
	public record Welcome(boolean available) {
	}
}

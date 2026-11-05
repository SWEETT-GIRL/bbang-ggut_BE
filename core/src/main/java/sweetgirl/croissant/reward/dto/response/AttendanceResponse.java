package sweetgirl.croissant.reward.dto.response;

import java.util.List;

/**
 * 출석 결과.
 *
 * @param boardDay    오늘 찍힌 칸(1~7)
 * @param crumbs      오늘 받은 양
 * @param alreadyDone 오늘 이미 출석했다. 이때 {@code rewards} 는 비어 있고 나머지는 오늘 결과 그대로다
 * @param nextBoardDay 내일 이어서 찍으면 찍힐 칸(7 일째 다음은 1). 완료 화면에서 "내일 N일째 +X" 를 미리 보여 준다
 * @param nextCrumbs  내일 이어서 찍으면 받을 양
 */
public record AttendanceResponse(
		int boardDay,
		int crumbs,
		boolean alreadyDone,
		List<RewardItemResponse> rewards,
		int nextBoardDay,
		int nextCrumbs) {
}

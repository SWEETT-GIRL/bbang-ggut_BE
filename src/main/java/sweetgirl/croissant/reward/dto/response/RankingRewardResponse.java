package sweetgirl.croissant.reward.dto.response;

import java.time.LocalDate;

import sweetgirl.croissant.reward.entity.RankingWeeklyReward;

/**
 * 받을 수 있는 랭킹 주간 보상 한 줄.
 *
 * @param weekStart 순위를 겨룬 주의 월요일
 * @param rank      그 주의 최종 순위(1~10)
 * @param crown     왕관이 딸린 보상인가(1 위)
 * @param expiresOn 이날까지 받을 수 있다
 */
public record RankingRewardResponse(Long id, LocalDate weekStart, int rank, int crumbs, boolean crown,
		LocalDate expiresOn) {

	public static RankingRewardResponse from(RankingWeeklyReward reward) {
		return new RankingRewardResponse(reward.getId(), reward.getWeekStart(), reward.getFinalRank(),
			reward.getCrumbs(), reward.hasCrown(), reward.lastClaimableDay());
	}
}

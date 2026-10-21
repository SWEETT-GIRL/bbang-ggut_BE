package sweetgirl.croissant.breadranking.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.breadranking.entity.BreadRankEntry;

/**
 * 내 빵 순위 한 줄.
 *
 * @param rank   등수. 아직 안 정했으면 null
 * @param rating 그 가게 × 빵 종류의 내 최신 평가. 태그가 없으면 null
 */
public record MyBreadRankEntryResponse(
		StoreSummaryResponse store,
		Integer rank,
		LocalDateTime rankedAt,
		MyBreadRatingResponse rating) {

	public static MyBreadRankEntryResponse of(BreadRankEntry entry, String thumbnailImageKey,
			MyBreadRatingResponse rating) {
		return new MyBreadRankEntryResponse(
			StoreSummaryResponse.of(entry.getStore(), thumbnailImageKey), entry.getRank(), entry.getRankedAt(), rating);
	}
}

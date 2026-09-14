package sweetgirl.croissant.recommendation.repository;

/**
 * 후보 SQL 한 행. 중심(상권 중심 또는 앵커)에서의 거리와 인기 점수를 같이 들고 온다 — 순서 정하기와 응답에 둘 다 쓴다.
 * thumbnailKey 는 가게 첫 사진(리사이즈 320 우선)이고 없으면 null.
 */
public record CandidateStoreRow(
		Long storeId,
		String name,
		String region3rd,
		double latitude,
		double longitude,
		String thumbnailKey,
		double distanceM,
		double popularityScore) {
}

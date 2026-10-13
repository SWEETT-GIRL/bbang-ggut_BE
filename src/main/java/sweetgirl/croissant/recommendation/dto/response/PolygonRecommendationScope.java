package sweetgirl.croissant.recommendation.dto.response;

/** 내부 저장용: Gemini 문장 갱신 후에도 동일한 지역·동네·거리 정책으로 재추천한다. */
public record PolygonRecommendationScope(
		String algorithmVersion, String boundaryVersion, long bindingId,
		double latitude, double longitude, int radiusM, int maxHopM, int distancePerLegM,
		int singleStoreApproachM, boolean exactArea,
		Long originStoreId, Double originLatitude, Double originLongitude) {
}

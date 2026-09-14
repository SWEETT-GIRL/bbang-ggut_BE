package sweetgirl.croissant.recommendation.repository;

import java.util.List;
import java.util.Map;

/**
 * 후보 SQL 한 행. 중심(상권 중심 또는 앵커)에서의 거리와 점수를 같이 들고 온다 — 순서 정하기와 응답에 둘 다 쓴다.
 * thumbnailKey 는 가게 첫 사진(리사이즈 320 우선)이고 없으면 null.
 *
 * <p>#661 점수: score 가 정렬 기준이고 tasteFit 은 사용자·가게 축 코사인(둘 다 없으면 0). tasteCandidateCount 는 이 조회의 풀
 * 전체에서 코사인이 임계를 넘은 가게 수(창 함수)라 모든 행이 같은 값이다 — 취향 폴백 판정에 쓴다. 축·태그·종류는 앵커 배지를
 * 자바에서 정하려고 실어 온다.
 */
public record CandidateStoreRow(
		Long storeId,
		String name,
		String region3rd,
		double latitude,
		double longitude,
		String thumbnailKey,
		double distanceM,
		double popularityScore,
		double score,
		double tasteFit,
		int tasteCandidateCount,
		Double axisTaste,
		Double axisTexture,
		Double axisFilling,
		Double axisVibe,
		Map<String, Double> tags,
		List<String> kinds) {

	/** 점수 항이 없는 행 — #660 형태의 픽스처·인기순만 있을 때. */
	public CandidateStoreRow(Long storeId, String name, String region3rd, double latitude, double longitude,
			String thumbnailKey, double distanceM, double popularityScore) {
		this(storeId, name, region3rd, latitude, longitude, thumbnailKey, distanceM, popularityScore, 0, 0, 0,
			null, null, null, null, Map.of(), List.of());
	}

	public boolean hasAxis() {
		return axisTaste != null && axisTexture != null && axisFilling != null && axisVibe != null;
	}
}

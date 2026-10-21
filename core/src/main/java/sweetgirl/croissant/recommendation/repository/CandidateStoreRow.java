package sweetgirl.croissant.recommendation.repository;

import java.util.List;
import java.util.Map;

import sweetgirl.croissant.personalization.entity.TasteLabelVector;

/**
 * 후보 SQL 한 행. 중심(상권 중심 또는 앵커)에서의 거리와 점수를 같이 들고 온다 — 순서 정하기와 응답에 둘 다 쓴다.
 * thumbnailKey 는 가게 첫 사진(리사이즈 320 우선)이고 없으면 null.
 *
 * <p>#661 점수: score 가 정렬 기준이고 tasteFit 은 사용자·가게 축 코사인(둘 다 없으면 0). tasteCandidateCount 는 이 조회의 풀
 * 전체에서 코사인이 임계를 넘은 가게 수(창 함수)라 모든 행이 같은 값이다 — 취향 폴백 판정에 쓴다. 축·태그·종류는 앵커 배지를
 * 자바에서 정하려고 실어 온다.
 *
 * <p>kinds 는 메뉴 ∪ 리뷰 언급 종류(종류 겹침 점수의 재료), signatureKinds 는 대표 메뉴를 종류로 푼 것(#762 — "{종류} 취향" 배지는
 * 가게가 그 종류로 알려졌을 때만 붙인다). 없으면 빈 목록.
 *
 * <p>labels 는 store_recommendation_feature 의 아홉 값이다(0~1). axis 와 <b>같은 가게의 다른 표현</b>이고 부호도 다르다 —
 * axisFilling 은 꽉 참이 −1 인데 labels.fillingRich 는 꽉 참이 1 이다. 한쪽 값을 다른 쪽으로 옮기지 않는다. 적재 전이거나
 * 라벨을 못 만든 가게는 null 이다(상권 안 ACTIVE 가게의 27%, 다만 라벨이 4곳 미만인 상권은 없다).
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
		List<String> kinds,
		List<String> signatureKinds,
		TasteLabelVector labels) {

	/**
	 * 라벨 없는 행 — 아직 적재 전이거나 라벨을 못 만든 가게, 그리고 라벨을 쓰지 않는 경로(이유 문장 · 픽스처).
	 * 라벨이 없는 것은 오류가 아니라 정상 상태다. 점수 쪽이 {@link #hasLabels()} 로 갈라 다룬다.
	 */
	public CandidateStoreRow(Long storeId, String name, String region3rd, double latitude, double longitude,
			String thumbnailKey, double distanceM, double popularityScore, double score, double tasteFit,
			int tasteCandidateCount, Double axisTaste, Double axisTexture, Double axisFilling, Double axisVibe,
			Map<String, Double> tags, List<String> kinds, List<String> signatureKinds) {
		this(storeId, name, region3rd, latitude, longitude, thumbnailKey, distanceM, popularityScore, score,
			tasteFit, tasteCandidateCount, axisTaste, axisTexture, axisFilling, axisVibe, tags, kinds,
			signatureKinds, null);
	}

	/** 점수 항이 없는 행 — #660 형태의 픽스처·인기순만 있을 때. */
	public CandidateStoreRow(Long storeId, String name, String region3rd, double latitude, double longitude,
			String thumbnailKey, double distanceM, double popularityScore) {
		this(storeId, name, region3rd, latitude, longitude, thumbnailKey, distanceM, popularityScore, 0, 0, 0,
			null, null, null, null, Map.of(), List.of(), List.of(), null);
	}

	public boolean hasAxis() {
		return axisTaste != null && axisTexture != null && axisFilling != null && axisVibe != null;
	}

	/** 라벨로 맞댈 수 있는 가게인가 — 아홉이 함께 차거나 함께 비므로 하나만 봐도 되지만 계약을 그대로 확인한다. */
	public boolean hasLabels() {
		return labels != null && labels.isComplete();
	}
}

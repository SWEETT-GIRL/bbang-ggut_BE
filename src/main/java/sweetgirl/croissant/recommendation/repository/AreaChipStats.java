package sweetgirl.croissant.recommendation.repository;

/**
 * 상권(동네 행) 하나의 칩 재료 — 후보와 같은 소속 규칙로 센 가게 수와, 칩 순서를 정하는 신호 셋(#661 §5.2):
 * 이 세션에서 상세를 본 가게 수 · 내가 저장한 가게 수 · 취향 후보(코사인 > 임계) 수. 칩은 도장 칸 단위로 이 값을 합친다.
 */
public record AreaChipStats(
		Long areaId,
		int storeCount,
		int savedStoreCount,
		int sessionViewCount,
		int tasteCandidateCount) {

	/** 신호 없이 가게 수와 저장 여부만 있던 #660 형태 — 동네 선택(resolveNeighborhood)과 테스트 픽스처가 쓴다. */
	public AreaChipStats(Long areaId, int storeCount, boolean hasSavedStore) {
		this(areaId, storeCount, hasSavedStore ? 1 : 0, 0, 0);
	}

	public boolean hasSavedStore() {
		return savedStoreCount > 0;
	}
}

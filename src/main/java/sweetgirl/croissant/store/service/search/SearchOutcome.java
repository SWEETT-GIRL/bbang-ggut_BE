package sweetgirl.croissant.store.service.search;

import java.util.Locale;

/**
 * 검색 한 건이 사용자가 입력한 말을 실제로 돌려줬는지에 대한 판정 결과.
 *
 * <p>Prometheus {@code outcome} 태그 값으로 노출되므로 값의 개수를 고정된 소수로 유지한다.
 * 실패를 한 값으로 뭉치지 않고 판정 신뢰도에 따라 나누는 이유는, 오탐이 섞인 값 때문에
 * 지표 전체를 못 믿게 되는 상황을 피하기 위해서다.
 */
public enum SearchOutcome {

	/** 결과가 있고, 사용자가 입력한 말이 그 안에 있다. */
	HIT,

	/** 빵 분해 폴백까지 돌렸는데도 0건. */
	EMPTY,

	/** 1차가 0건이라 빵 단어를 분해해 다시 검색한 결과. 시스템이 검색어를 깎았으므로 원문과 다른 것이 확실하다. */
	FALLBACK,

	/** 검색어가 전부 지역·빵으로 분류됐는데도 담당 필드에 없다. 사전에 있는 말들만 쓴 검색이라 판정 신뢰도가 높다. */
	MISMATCH,

	/** 사전에 없는 말이 섞여 검색어 전체를 통째로 대조했고 실패했다. 지역어 + 가게명 조합 등에서 오탐이 섞일 수 있다. */
	MISMATCH_LOOSE,

	/** 시간 안에 답을 못 했다 — SQL 이 statement 타임아웃에 끊겼다. 쿼리가 아니라 DB·커넥션이 멈췄다는 신호다. */
	TIMEOUT;

	public String tagValue() {
		return name().toLowerCase(Locale.ROOT);
	}
}

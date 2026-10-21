package sweetgirl.croissant.store.enums;

/**
 * 급부상 판정에 쓰는 회원 행동. 가중치는 {@code store.trending.weights} 로 정한다.
 *
 * <p>평소 수준(baseline)은 같은 행동을 user_store_interaction 에서 세므로, 각 신호가 그 표의 어느 type 인지 함께 둔다 — 최근과 평소를
 * 같은 눈금으로 재야 둘을 나눈 배율이 의미를 가진다.
 */
public enum TrendingSignal {
	VIEW("STORE_VIEW"),
	SAVE("SAVE"),
	PLAN("PLAN"),
	COURSE_ADD("COURSE_ADD"),
	RECORD("RECORD_STORE");

	private final String interactionType;

	TrendingSignal(String interactionType) {
		this.interactionType = interactionType;
	}

	/** user_store_interaction.type 값 */
	public String interactionType() {
		return interactionType;
	}
}

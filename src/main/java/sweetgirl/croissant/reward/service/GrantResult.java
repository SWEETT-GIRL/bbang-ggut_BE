package sweetgirl.croissant.reward.service;

/**
 * 적립을 시도한 결과. 앱 응답의 {@code rewards[]} 한 칸({@code amount · from · to · capped})을 만드는 재료다.
 *
 * @param outcome 실제로 줬는가, 안 줬다면 왜인가
 * @param amount  준 양. 주지 않았으면 0
 * @param from    적립 전 잔액
 * @param to      적립 후 잔액. 주지 않았으면 {@code from} 과 같다
 */
public record GrantResult(Outcome outcome, int amount, int from, int to) {

	public enum Outcome {
		/** 줬다. */
		GRANTED,
		/** 같은 보상을 이미 줬다(멱등 키 충돌). */
		ALREADY_GRANTED,
		/** 하루·주 상한에 걸렸다. */
		CAPPED,
		/** 제재 중이라 적립이 멈췄다. */
		RESTRICTED
	}

	static GrantResult granted(int amount, int balanceBefore) {
		return new GrantResult(Outcome.GRANTED, amount, balanceBefore, balanceBefore + amount);
	}

	static GrantResult notGranted(Outcome outcome, int balance) {
		return new GrantResult(outcome, 0, balance, balance);
	}

	public boolean isGranted() {
		return outcome == Outcome.GRANTED;
	}

	public boolean isCapped() {
		return outcome == Outcome.CAPPED;
	}
}

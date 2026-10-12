package sweetgirl.croissant.reward.service;

/**
 * 차감을 시도한 결과.
 *
 * @param spent  이번 호출에서 실제로 뺐는가. 같은 키로 이미 뺀 적이 있으면 false
 * @param amount 뺀 양. 빼지 않았으면 0
 * @param from   차감 전 잔액
 * @param to     차감 후 잔액
 */
public record SpendResult(boolean spent, int amount, int from, int to) {

	static SpendResult spent(int amount, int balanceBefore) {
		return new SpendResult(true, amount, balanceBefore, balanceBefore - amount);
	}

	static SpendResult alreadySpent(int balance) {
		return new SpendResult(false, 0, balance, balance);
	}
}

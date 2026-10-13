package sweetgirl.croissant.reward.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import sweetgirl.croissant.reward.entity.CrumbLedgerEntry;
import sweetgirl.croissant.reward.enums.CrumbReason;

/**
 * 받은 내역 한 장. 최신순이다.
 *
 * @param nextCursor 다음 장을 부를 때 넘기는 값. 더 없으면 null
 */
public record RewardHistoryResponse(List<Item> items, Long nextCursor) {

	/** @param amount 받은 것은 양수, 쓴 것은 음수 */
	public record Item(Long id, int amount, CrumbReason reason, int balanceAfter, LocalDateTime createdAt) {

		public static Item from(CrumbLedgerEntry entry) {
			return new Item(entry.getId(), entry.getAmount(), entry.getReason(), entry.getBalanceAfter(),
				entry.getCreatedAt());
		}
	}
}

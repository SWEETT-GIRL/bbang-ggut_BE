package sweetgirl.croissant.reward.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.enums.RewardItemType;
import sweetgirl.croissant.reward.service.GrantResult;
import sweetgirl.croissant.reward.service.SpendResult;

/**
 * 보상 한 칸. 보상이 생기는 모든 응답이 {@code rewards[]} 에 같은 모양으로 싣고, 앱은 배열 순서대로 연출한다.
 *
 * <p>종류마다 쓰는 필드가 다르다 — 안 쓰는 필드는 응답에서 빠진다.
 *
 * @param id 아직 연출하지 못한 보상({@code pendingRewards})일 때만 있다. 연출을 끝내고 ack 할 때 이 값을 보낸다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RewardItemResponse(
		Long id,
		RewardItemType type,
		Integer amount,
		Integer from,
		Integer to,
		CrumbReason reason,
		Boolean capped,
		String planet,
		String stage,
		Long courseId,
		String step) {

	/** 부스러기 적립 결과 한 칸. 상한에 걸렸으면 {@code capped: true, amount: 0} 으로 나간다. */
	public static RewardItemResponse crumbs(GrantResult result, CrumbReason reason) {
		return new RewardItemResponse(null, RewardItemType.CRUMBS, result.amount(), result.from(), result.to(),
			reason, result.isCapped(), null, null, null, null);
	}

	/** 부스러기를 썼다. {@code amount} 가 음수다. */
	public static RewardItemResponse spent(SpendResult result, CrumbReason reason) {
		return new RewardItemResponse(null, RewardItemType.CRUMBS, -result.amount(), result.from(), result.to(),
			reason, false, null, null, null, null);
	}

	/** 빵 칸(기록 수)이 올랐다. */
	public static RewardItemResponse bread(int from, int to) {
		return new RewardItemResponse(null, RewardItemType.BREAD, null, from, to, null, null, null, null, null,
			null);
	}

	/** 레벨이 올랐다. 오른 뒤에 선 행성과 고도를 같이 싣는다. */
	public static RewardItemResponse levelUp(int from, int to, String planet, String stage) {
		return new RewardItemResponse(null, RewardItemType.LEVEL_UP, null, from, to, null, null, planet, stage,
			null, null);
	}

	public RewardItemResponse withId(Long id) {
		return new RewardItemResponse(id, type, amount, from, to, reason, capped, planet, stage, courseId, step);
	}
}

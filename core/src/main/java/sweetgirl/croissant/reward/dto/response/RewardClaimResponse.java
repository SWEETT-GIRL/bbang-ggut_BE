package sweetgirl.croissant.reward.dto.response;

import java.util.List;

/**
 * 한 번만 받는 보상(환영 선물 등)을 받으려 한 결과.
 *
 * @param alreadyDone 이미 받았거나 받을 대상이 아니다. 이때 {@code rewards} 는 비어 있다
 */
public record RewardClaimResponse(boolean alreadyDone, List<RewardItemResponse> rewards) {

	public static RewardClaimResponse done() {
		return new RewardClaimResponse(true, List.of());
	}
}

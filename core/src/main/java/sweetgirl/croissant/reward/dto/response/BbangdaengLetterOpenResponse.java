package sweetgirl.croissant.reward.dto.response;

import java.util.List;

/**
 * 편지를 연 결과.
 *
 * @param alreadyOpened 이미 열었던 편지다. 이때 {@code rewards} 는 비어 있다
 */
public record BbangdaengLetterOpenResponse(String body, boolean alreadyOpened, List<RewardItemResponse> rewards) {
}

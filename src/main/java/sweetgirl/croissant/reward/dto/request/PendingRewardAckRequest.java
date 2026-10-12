package sweetgirl.croissant.reward.dto.request;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/** 앱이 연출을 끝낸 {@code pendingRewards} 의 id 들. */
public record PendingRewardAckRequest(
		@NotEmpty List<@NotNull Long> ids) {
}

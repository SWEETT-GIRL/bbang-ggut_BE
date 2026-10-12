package sweetgirl.croissant.breadlog.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import sweetgirl.croissant.reward.dto.response.RewardItemResponse;

/**
 * @param rewards 이 저장으로 생긴 보상. 앱은 배열 순서대로 연출한다. 게스트이거나 같은 요청의 재전송이면 비어 있다
 */
public record BreadRecordCreateResponse(Long recordId, LocalDateTime createdAt, List<RewardItemResponse> rewards) {

	public BreadRecordCreateResponse(Long recordId, LocalDateTime createdAt) {
		this(recordId, createdAt, List.of());
	}
}

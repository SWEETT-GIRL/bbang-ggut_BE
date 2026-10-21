package sweetgirl.croissant.breadlog.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import sweetgirl.croissant.breadlog.entity.BreadRecordTag;
import sweetgirl.croissant.reward.dto.response.RewardItemResponse;

/**
 * @param rewards 이 저장으로 생긴 보상. 앱은 배열 순서대로 연출한다. 게스트이거나 같은 요청의 재전송이면 비어 있다
 * @param tags    저장된 빵 태그의 이름과 분류. 앱이 저장 직후 "순위 매겨 볼까요"를 띄울 분류를 고른다
 */
public record BreadRecordCreateResponse(
		Long recordId,
		LocalDateTime createdAt,
		List<RewardItemResponse> rewards,
		List<Tag> tags) {

	public BreadRecordCreateResponse(Long recordId, LocalDateTime createdAt) {
		this(recordId, createdAt, List.of());
	}

	public BreadRecordCreateResponse(Long recordId, LocalDateTime createdAt, List<RewardItemResponse> rewards) {
		this(recordId, createdAt, rewards, List.of());
	}

	public static List<Tag> tagsOf(List<BreadRecordTag> tags) {
		return tags.stream().map(tag -> new Tag(tag.getName(), tag.resolvedCategory())).toList();
	}

	/** 저장된 빵 태그 하나. breadCategory 는 기록 조회의 tags[].breadCategory 와 같은 값이다. */
	public record Tag(String name, String breadCategory) {
	}
}

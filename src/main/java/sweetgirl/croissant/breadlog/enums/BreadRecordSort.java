package sweetgirl.croissant.breadlog.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum BreadRecordSort {

	/** (좋아요 + 댓글 × 2) × 날짜 가중(7일 안 1.0 / 30일 안 0.6 / 그 뒤 0.3), 동점은 최신 */
	RECOMMENDED("추천순"),
	LATEST("최신순");

	private final String description;
}

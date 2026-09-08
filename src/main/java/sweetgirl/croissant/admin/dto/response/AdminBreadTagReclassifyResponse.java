package sweetgirl.croissant.admin.dto.response;

import java.util.Map;

/**
 * @param total   전체 태그 수
 * @param changed 분류가 바뀐 태그 수
 * @param moves   "이전분류 → 새분류" → 건수. 어떤 규칙이 효과를 냈는지 보는 용도
 */
public record AdminBreadTagReclassifyResponse(
		long total,
		long changed,
		Map<String, Long> moves) {
}

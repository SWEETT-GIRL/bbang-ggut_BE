package sweetgirl.croissant.course.dto.response;

import java.time.LocalDateTime;

/**
 * 아직 DB 에 안 간 초안 하나(GET drafts). 이름·얼굴은 싣지 않는다 — 앱이 상세 {@code members[]} 에서 memberId 로 찾는다.
 * 빈 content 는 "지우기 예약" — 앱은 메모 없음으로 그린다.
 */
public record MemoDraftResponse(
		Long storeId,
		Long memberId,
		String content,
		long seq,
		boolean isMe,
		LocalDateTime updatedAt) {
}

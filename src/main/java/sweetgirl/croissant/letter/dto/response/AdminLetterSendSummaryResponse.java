package sweetgirl.croissant.letter.dto.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 보낸 편지 목록의 한 줄 — 발송 한 번.
 *
 * @param sendId 발송 id(notification id)
 * @param name 어드민이 붙인 발송 이름
 * @param bodyPreview 보낸 편지 앞부분
 * @param push 알림을 울렸는지
 * @param recipientCount 받은 사람 수
 * @param repliedCount 이 편지 뒤로 답장한 사람 수
 */
public record AdminLetterSendSummaryResponse(
		Long sendId,
		String name,
		String bodyPreview,
		boolean push,
		LocalDateTime sentAt,
		int recipientCount,
		int repliedCount) {

	public record Page(List<AdminLetterSendSummaryResponse> sends, boolean hasNext) {
	}
}

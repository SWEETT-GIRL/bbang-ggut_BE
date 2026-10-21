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
 * @param pushResult 알림을 울린 발송이면 몇 명에게 갔는지. 안 울렸으면 null
 */
public record AdminLetterSendSummaryResponse(
		Long sendId,
		String name,
		String bodyPreview,
		boolean push,
		LocalDateTime sentAt,
		int recipientCount,
		int repliedCount,
		PushResult pushResult) {

	/**
	 * 알림이 몇 명에게 갔는지.
	 *
	 * @param sent FCM이 받아 준 사람(기기 표시 여부는 별도)
	 * @param failed 보냈는데 실패한 사람
	 * @param notSent 안 보낸 사람 — 알림을 껐거나, 알림 권한이 없어 기기 토큰이 없다. 아직 보내는 중이면 여기 들어 있다
	 */
	public record PushResult(int sent, int failed, int notSent) {
	}

	public record Page(List<AdminLetterSendSummaryResponse> sends, boolean hasNext) {
	}
}

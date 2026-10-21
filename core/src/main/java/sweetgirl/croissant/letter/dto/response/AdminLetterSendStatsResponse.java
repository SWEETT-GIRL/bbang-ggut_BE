package sweetgirl.croissant.letter.dto.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 발송 한 번의 집계 — 몇 명이 받았고, 몇 명이 답했고, 보기 번호별로 몇 명인지.
 *
 * <p>번호는 답장 글에 들어 있는 1~보기 개수의 숫자(①~ 포함)로 센다. "2", "2번입니다!" 모두 2다.
 * 한 사람이 여러 번 답해도 번호마다 한 번만 센다. 숫자가 없는 답장은 {@code unnumbered}로 따로 준다.
 *
 * @param counts 보기 번호(1부터)마다 고른 사람 수. 보기 개수를 안 정한 발송이면 빈 목록
 * @param replies 답한 사람마다 — 읽은 번호와 답장 원문
 */
public record AdminLetterSendStatsResponse(
		Long sendId,
		String name,
		String body,
		boolean push,
		LocalDateTime sentAt,
		Integer choiceCount,
		int recipientCount,
		int repliedCount,
		List<ChoiceCount> counts,
		int unnumberedCount,
		List<Reply> replies,
		List<Recipient> recipients,
		AdminLetterSendSummaryResponse.PushResult pushResult) {

	public record ChoiceCount(int choice, int count) {
	}

	/** @param choices 답장에서 읽은 번호. 없으면 빈 목록 */
	public record Reply(Long threadId, Long memberId, String nickname, List<Integer> choices,
			String text, LocalDateTime repliedAt) {
	}

	/** 받은 사람 — 누르면 그 대화를 연다. */
	public record Recipient(Long threadId, Long memberId, String nickname, boolean replied) {
	}
}

package sweetgirl.croissant.letter.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.letter.enums.LetterCategory;

/**
 * 어드민 편지함 목록의 한 줄. 누구와의 대화이고, 마지막 말이 무엇이며, 답장을 기다리는 게 몇 통인지.
 *
 * <p>{@code lastMessagePreview}는 목록에서 한 줄로 잘려 보이므로 서버가 미리 자른다 —
 * 대화 하나에 2000자가 들어 있어도 목록에 다 실어 보낼 이유가 없다.
 */
public record AdminLetterThreadResponse(
		Long threadId,
		Long memberId,
		String nickname,
		boolean withdrawn,
		String lastMessagePreview,
		LocalDateTime lastMessageAt,
		long unreadCount,
		/** 가장 최근 사용자 메시지의 유형. 무슨 건인지 목록에서 바로 보이라고 얹는다. */
		LetterCategory category) {
}

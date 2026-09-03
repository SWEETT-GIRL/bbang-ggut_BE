package sweetgirl.croissant.letter.dto.response;

import java.util.List;

/** 대화 하나를 펼친 것. 사용자 쪽과 같은 최신순이고, 어드민 화면이 뒤집어 그린다. */
public record AdminLetterThreadDetailResponse(
		Long threadId,
		Long memberId,
		String nickname,
		boolean withdrawn,
		List<LetterMessageResponse> messages,
		boolean hasNext) {
}

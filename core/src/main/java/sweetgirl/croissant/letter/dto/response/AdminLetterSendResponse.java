package sweetgirl.croissant.letter.dto.response;

import java.util.List;

/**
 * 여러 명에게 보낸 결과.
 *
 * <p>보낸 수만 돌려주면 "30명한테 보냈는데 28명만 갔다"를 알 수 없다. 못 보낸 회원 id를 같이
 * 주면 어드민 화면이 누가 빠졌는지 그대로 보여줄 수 있다 — 탈퇴했거나 없는 id다.
 */
public record AdminLetterSendResponse(
		int sentCount,
		List<Long> skippedMemberIds) {
}

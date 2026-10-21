package sweetgirl.croissant.letter.dto.response;

import java.util.List;

/**
 * 내 편지함. 아직 편지가 오간 적 없으면 빈 목록이다 — 스레드가 없다는 것과 편지가
 * 없다는 것을 앱이 구분할 이유가 없어서 404를 내지 않는다.
 */
public record LetterListResponse(
		List<LetterMessageResponse> messages,
		boolean hasNext,
		/**
		 * 이 대화가 닫혀 있는지. 어드민이 해결로 닫았거나, 오래 조용했거나 — 앱 입장에서는
		 * 같은 판단이라 한 값으로 내린다.
		 *
		 * <p>앱은 이 값으로 "어떤 이야기인지 골라주세요"를 다시 띄울지 정한다. 닫혀 있으면
		 * 이번 방문은 새 용건이고, 열려 있으면 하던 상담을 이어가는 중이다.
		 *
		 * <p>조용함까지 서버가 재는 이유가 둘 있다. {@code sentAt}에 시간대가 없어 앱에서
		 * 재면 다른 시간대의 사용자가 어긋나고, 기준 일수를 바꿀 때 앱 배포 없이 서버만
		 * 고치면 된다.
		 */
		boolean closed) {

	public static LetterListResponse empty() {
		return new LetterListResponse(List.of(), false, false);
	}
}

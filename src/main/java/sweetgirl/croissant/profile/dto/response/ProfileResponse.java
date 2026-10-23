package sweetgirl.croissant.profile.dto.response;

/**
 * 남의(또는 내) 공개 프로필. {@code blocked} 가 true 면 "볼 수 없는 프로필이에요" — 차단 관계이거나 프로필 제한 중이고,
 * 그때는 memberId 말고 전부 비어 있다. 필드는 더하기만 한다.
 */
public record ProfileResponse(
		Long memberId,
		boolean blocked,
		boolean isMe,
		String nickname,
		/** 지금 데리고 다니는 캐릭터 코드. 세계관을 시작하지 않았으면 마스코트 */
		String characterCode,
		/** 그 캐릭터의 얼굴(front) 이미지 키. 없으면 null */
		String characterImageKey,
		/** 홈에 놓은 소품 코드. 없으면 null */
		String homePropItem,
		Integer level,
		/** 행성 코드 — 앱이 홈(/members/me/world)과 같은 그림을 고를 때 쓴다 */
		String planetId,
		String planetName,
		/** "성수동 빵순이". 공개 빵로그가 한 동네에 3개 이상이어야 생긴다. 없으면 null */
		String title,
		long publicRecordCount,
		long followerCount,
		long followingCount,
		boolean followedByMe,
		boolean followsMe) {

	public static ProfileResponse blocked(Long memberId) {
		return new ProfileResponse(memberId, true, false, null, null, null, null, null, null, null, null,
			0, 0, 0, false, false);
	}
}

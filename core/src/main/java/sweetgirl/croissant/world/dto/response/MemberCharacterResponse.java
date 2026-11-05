package sweetgirl.croissant.world.dto.response;

import sweetgirl.croissant.world.entity.MemberCharacter;
import sweetgirl.croissant.world.enums.AcquiredBy;
import sweetgirl.croissant.world.enums.CharacterStatus;

/**
 * 회원이 가진 친구 하나. {@code adsRequired} 는 그 친구가 속한 행성 행의 값이라 행성마다 다를 수 있다.
 */
public record MemberCharacterResponse(
		String code,
		CharacterStatus status,
		int adViews,
		int adsRequired,
		AcquiredBy acquiredBy,
		/** 늘 null. 친구 이름 짓기는 2026-10-21 에 없앴고, 설치된 앱이 이 필드를 읽어서 자리만 남겼다 */
		String nickname,
		/** 부스러기로 깨울 수 있는가. 광고를 한 편이라도 본 친구와 이미 깨어난 친구는 안 된다 */
		boolean canWakeWithCrumbs) {

	public static MemberCharacterResponse of(MemberCharacter memberCharacter, int adsRequired) {
		return new MemberCharacterResponse(
			memberCharacter.characterCode(),
			memberCharacter.getStatus(),
			memberCharacter.getAdViews(),
			adsRequired,
			memberCharacter.getAcquiredBy(),
			null,
			!memberCharacter.isAwake() && memberCharacter.getAdViews() == 0);
	}
}

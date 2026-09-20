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
		AcquiredBy acquiredBy) {

	public static MemberCharacterResponse of(MemberCharacter memberCharacter, int adsRequired) {
		return new MemberCharacterResponse(
			memberCharacter.characterCode(),
			memberCharacter.getStatus(),
			memberCharacter.getAdViews(),
			adsRequired,
			memberCharacter.getAcquiredBy());
	}
}

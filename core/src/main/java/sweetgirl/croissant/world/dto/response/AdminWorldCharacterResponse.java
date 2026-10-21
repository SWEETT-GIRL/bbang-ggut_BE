package sweetgirl.croissant.world.dto.response;

import java.util.Map;

import sweetgirl.croissant.world.entity.WorldCharacter;

/**
 * @param assets   올라가 있는 포즈 → 그림 키(URL 이 아니라 키 그대로). 어드민은 무엇이 올라갔는지를 본다
 * @param ownedBy  이 캐릭터를 가진 회원 수. 비활성으로 돌리기 전에 영향 범위를 보라고 준다
 */
public record AdminWorldCharacterResponse(
		String code,
		String name,
		String description,
		String planetCode,
		int sortOrder,
		boolean mascot,
		boolean active,
		Map<String, String> assets,
		long ownedBy) {

	public static AdminWorldCharacterResponse of(
			WorldCharacter character,
			Map<String, String> assets,
			long ownedBy) {
		return new AdminWorldCharacterResponse(
			character.getCode(),
			character.getName(),
			character.getDescription(),
			character.getPlanetCode(),
			character.getSortOrder(),
			character.isMascot(),
			character.isActive(),
			assets,
			ownedBy);
	}
}

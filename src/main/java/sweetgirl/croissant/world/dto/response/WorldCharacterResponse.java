package sweetgirl.croissant.world.dto.response;

import java.util.Map;

import sweetgirl.croissant.world.entity.WorldCharacter;

/**
 * 캐릭터 한 명. {@code assets} 는 포즈 이름 → 그림 URL 이고, <b>올라간 포즈만</b> 들어 있다.
 * 앱은 아는 코드면 번들 그림을, 모르면 이 URL 을 쓴다.
 */
public record WorldCharacterResponse(
		String code,
		String name,
		String planet,
		int sortOrder,
		boolean mascot,
		Map<String, String> assets) {

	public static WorldCharacterResponse of(WorldCharacter character, Map<String, String> assets) {
		return new WorldCharacterResponse(
			character.getCode(),
			character.getName(),
			character.getPlanetCode(),
			character.getSortOrder(),
			character.isMascot(),
			assets);
	}
}

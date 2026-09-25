package sweetgirl.croissant.world.dto.response;

import java.util.Map;

import sweetgirl.croissant.world.entity.WorldCharacter;

/**
 * 캐릭터 한 명. {@code assets} 는 포즈 이름 → 그림 URL 이고, <b>올라간 포즈만</b> 들어 있다.
 * 앱은 아는 코드면 번들 그림을, 모르면 이 URL 을 쓴다. {@code description} 은 한 줄 소개이고, 없으면 null 이다.
 */
public record WorldCharacterResponse(
		String code,
		String name,
		String description,
		String planet,
		int sortOrder,
		boolean mascot,
		Map<String, String> assets) {

	public static WorldCharacterResponse of(WorldCharacter character, Map<String, String> assets) {
		return new WorldCharacterResponse(
			character.getCode(),
			character.getName(),
			character.getDescription(),
			character.getPlanetCode(),
			character.getSortOrder(),
			character.isMascot(),
			assets);
	}
}

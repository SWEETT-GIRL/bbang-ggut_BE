package sweetgirl.croissant.world.dto.response;

import java.util.List;

/**
 * 회원과 무관한 전역 카탈로그. 앱은 {@code version} 만 비교해 다시 받을지 정한다.
 */
public record WorldCatalogResponse(
		int version,
		List<WorldPlanetResponse> planets,
		List<WorldCharacterResponse> characters) {
}

package sweetgirl.croissant.world.dto.response;

import java.util.List;

import sweetgirl.croissant.world.entity.WorldPlanet;
import sweetgirl.croissant.world.enums.PlanetGroup;

/**
 * 행성 한 칸. {@code span} 은 절대 임계값이 아니라 이 행성을 졸업하는 데 필요한 기록 수이며,
 * 절대 도착점은 {@code unlockAfter} 사슬을 따라 누적해 얻는다.
 */
public record WorldPlanetResponse(
		String code,
		String name,
		PlanetGroup group,
		int sortOrder,
		int span,
		String unlockAfter,
		boolean selectable,
		int rouletteSize,
		int adsPerWake,
		boolean referralOk,
		String imageUrl,
		List<WorldStageResponse> stages) {

	public static WorldPlanetResponse of(
			WorldPlanet planet,
			String imageUrl,
			List<WorldStageResponse> stages) {
		return new WorldPlanetResponse(
			planet.getCode(),
			planet.getName(),
			planet.getPlanetGroup(),
			planet.getSortOrder(),
			planet.getSpan(),
			planet.getUnlockAfter(),
			planet.isSelectable(),
			planet.getRouletteSize(),
			planet.getAdsPerWake(),
			planet.isReferralOk(),
			imageUrl,
			stages);
	}
}

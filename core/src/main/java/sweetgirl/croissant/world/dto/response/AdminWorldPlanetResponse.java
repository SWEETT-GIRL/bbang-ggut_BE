package sweetgirl.croissant.world.dto.response;

import sweetgirl.croissant.world.entity.WorldPlanet;
import sweetgirl.croissant.world.enums.PlanetGroup;

/**
 * 어드민이 보는 행성 한 줄. 앱용({@link WorldPlanetResponse})과 달리 {@code imageKey} 를 날것으로
 * 보여준다 — 어드민은 어떤 키가 올라가 있는지를 봐야 하기 때문이다.
 *
 * @param arriveAt 여정을 따라 누적한 도착점. 앱이 보는 임계값과 같은 값이라, 어드민이 span 을 고칠 때
 *                 결과를 바로 확인할 수 있다
 */
public record AdminWorldPlanetResponse(
		String code,
		String name,
		PlanetGroup group,
		int sortOrder,
		int span,
		Integer arriveAt,
		String unlockAfter,
		boolean selectable,
		int rouletteSize,
		int adsPerWake,
		boolean referralOk,
		String imageKey,
		boolean active) {

	public static AdminWorldPlanetResponse of(WorldPlanet planet, Integer arriveAt) {
		return new AdminWorldPlanetResponse(
			planet.getCode(),
			planet.getName(),
			planet.getPlanetGroup(),
			planet.getSortOrder(),
			planet.getSpan(),
			arriveAt,
			planet.getUnlockAfter(),
			planet.isSelectable(),
			planet.getRouletteSize(),
			planet.getAdsPerWake(),
			planet.isReferralOk(),
			planet.getImageKey(),
			planet.isActive());
	}
}

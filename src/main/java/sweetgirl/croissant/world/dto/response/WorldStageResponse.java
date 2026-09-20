package sweetgirl.croissant.world.dto.response;

import sweetgirl.croissant.world.entity.WorldPlanetStage;

/**
 * 고도 한 단계. {@code offset} 은 행성 도착점 기준 <b>상대</b> 위치다 — 앱이 절대 누적값으로
 * 바꿔 쓰려면 그 행성의 도착점을 더해야 한다.
 */
public record WorldStageResponse(
		String code,
		String name,
		int stageOrder,
		int offset,
		String imageUrl) {

	public static WorldStageResponse of(WorldPlanetStage stage, String imageUrl) {
		return new WorldStageResponse(
			stage.getCode(),
			stage.getName(),
			stage.getStageOrder(),
			stage.getOffsetAt(),
			imageUrl);
	}
}

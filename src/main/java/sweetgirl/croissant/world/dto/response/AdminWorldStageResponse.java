package sweetgirl.croissant.world.dto.response;

import sweetgirl.croissant.world.entity.WorldPlanetStage;

/**
 * @param offsetAt   행성 도착점 기준 상대 위치
 * @param absoluteAt 실제 누적 기록 수(행성 도착점 + offset). 어드민이 임계값을 눈으로 확인하라고 준다
 */
public record AdminWorldStageResponse(
		Long id,
		String planetCode,
		String code,
		String name,
		int stageOrder,
		int offsetAt,
		Integer absoluteAt,
		String imageKey) {

	public static AdminWorldStageResponse of(WorldPlanetStage stage, Integer planetArriveAt) {
		return new AdminWorldStageResponse(
			stage.getId(),
			stage.getPlanetCode(),
			stage.getCode(),
			stage.getName(),
			stage.getStageOrder(),
			stage.getOffsetAt(),
			planetArriveAt == null ? null : planetArriveAt + stage.getOffsetAt(),
			stage.getImageKey());
	}
}

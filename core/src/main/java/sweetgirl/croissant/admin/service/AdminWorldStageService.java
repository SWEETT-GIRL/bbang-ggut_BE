package sweetgirl.croissant.admin.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.world.code.WorldErrorCode;
import sweetgirl.croissant.world.dto.request.AdminWorldStageCreateRequest;
import sweetgirl.croissant.world.dto.request.AdminWorldStageUpdateRequest;
import sweetgirl.croissant.world.dto.response.AdminWorldStageResponse;
import sweetgirl.croissant.world.entity.WorldPlanet;
import sweetgirl.croissant.world.entity.WorldPlanetStage;
import sweetgirl.croissant.world.exception.WorldException;
import sweetgirl.croissant.world.repository.WorldPlanetRepository;
import sweetgirl.croissant.world.repository.WorldPlanetStageRepository;
import sweetgirl.croissant.world.service.WorldCatalogVersionRaiser;

/**
 * 행성 안의 고도 단계를 만들고 고친다.
 *
 * <p>단계 수는 행성마다 달라도 된다 — 둘인 행성도 넷인 행성도 읽는 쪽이 같은 규칙으로 판정한다.
 * 그래서 "세 개여야 한다" 같은 검사는 하지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AdminWorldStageService {

	private final WorldPlanetRepository worldPlanetRepository;
	private final WorldPlanetStageRepository worldPlanetStageRepository;
	private final WorldCatalogVersionRaiser worldCatalogVersionRaiser;

	@Transactional(readOnly = true)
	public List<AdminWorldStageResponse> getStages(String planetCode) {
		getPlanet(planetCode);
		return worldPlanetStageRepository.findAllByPlanetCodeOrderByStageOrderAsc(planetCode).stream()
			.map(stage -> AdminWorldStageResponse.of(stage, null))
			.toList();
	}

	public Long create(String planetCode, AdminWorldStageCreateRequest request) {
		WorldPlanet planet = getPlanet(planetCode);
		validateOffset(planet, request.offsetAt());
		validateOrderIsFree(planetCode, request.stageOrder(), null);

		WorldPlanetStage saved = worldPlanetStageRepository.save(WorldPlanetStage.create(
			planetCode,
			request.code().strip(),
			request.name().strip(),
			request.stageOrder(),
			request.offsetAt(),
			normalize(request.imageKey())));
		worldCatalogVersionRaiser.raise();
		return saved.getId();
	}

	public void update(Long stageId, AdminWorldStageUpdateRequest request) {
		WorldPlanetStage stage = worldPlanetStageRepository.findById(stageId)
			.orElseThrow(() -> new WorldException(WorldErrorCode.WORLD_STAGE_NOT_FOUND));

		if (request.offsetAt() != null) {
			validateOffset(getPlanet(stage.getPlanetCode()), request.offsetAt());
		}
		if (request.stageOrder() != null) {
			validateOrderIsFree(stage.getPlanetCode(), request.stageOrder(), stageId);
		}

		stage.update(
			normalize(request.code()),
			normalize(request.name()),
			request.stageOrder(),
			request.offsetAt(),
			normalize(request.imageKey()));
		worldCatalogVersionRaiser.raise();
	}

	public void delete(Long stageId) {
		WorldPlanetStage stage = worldPlanetStageRepository.findById(stageId)
			.orElseThrow(() -> new WorldException(WorldErrorCode.WORLD_STAGE_NOT_FOUND));
		worldPlanetStageRepository.delete(stage);
		worldCatalogVersionRaiser.raise();
	}

	/**
	 * 상대 위치가 그 행성 구간을 넘으면 안 된다. 넘으면 아무도 못 닿는 단계가 되어, 배경이 영영
	 * 안 바뀌는데 이유를 알기 어렵다.
	 */
	private void validateOffset(WorldPlanet planet, int offsetAt) {
		if (offsetAt >= planet.getSpan()) {
			throw new WorldException(WorldErrorCode.WORLD_STAGE_INVALID);
		}
	}

	/** {@code (planet_code, stage_order)} 유니크라 DB 도 막지만, 저장 전에 말해 주는 편이 낫다. */
	private void validateOrderIsFree(String planetCode, int stageOrder, Long allowedStageId) {
		boolean taken = worldPlanetStageRepository
			.findAllByPlanetCodeOrderByStageOrderAsc(planetCode).stream()
			.anyMatch(stage -> stage.getStageOrder() == stageOrder
				&& !stage.getId().equals(allowedStageId));
		if (taken) {
			throw new WorldException(WorldErrorCode.WORLD_STAGE_INVALID);
		}
	}

	private WorldPlanet getPlanet(String planetCode) {
		return worldPlanetRepository.findById(planetCode)
			.orElseThrow(() -> new WorldException(WorldErrorCode.WORLD_PLANET_NOT_FOUND));
	}

	private String normalize(String value) {
		if (value == null) {
			return null;
		}
		String stripped = value.strip();
		return stripped.isEmpty() ? null : stripped;
	}
}

package sweetgirl.croissant.admin.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.world.code.WorldErrorCode;
import sweetgirl.croissant.world.dto.request.AdminWorldPlanetCreateRequest;
import sweetgirl.croissant.world.dto.request.AdminWorldPlanetUpdateRequest;
import sweetgirl.croissant.world.dto.response.AdminWorldPlanetResponse;
import sweetgirl.croissant.world.entity.WorldPlanet;
import sweetgirl.croissant.world.exception.WorldException;
import sweetgirl.croissant.world.repository.WorldPlanetRepository;
import sweetgirl.croissant.world.service.WorldCatalogVersionRaiser;

/**
 * 행성을 만들고 고친다. 행성 하나를 더하는 것만으로 앱에 새 여정이 열려야 한다 — 그게 안 되면
 * "데이터 주도"가 거짓말이 된다.
 *
 * <p>저장할 때마다 카탈로그 번호를 올린다. 안 올리면 앱도 서버 캐시도 옛 카탈로그를 계속 쓴다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AdminWorldPlanetService {

	private final WorldPlanetRepository worldPlanetRepository;
	private final WorldCatalogVersionRaiser worldCatalogVersionRaiser;

	@Transactional(readOnly = true)
	public List<AdminWorldPlanetResponse> getPlanets() {
		List<WorldPlanet> planets = worldPlanetRepository.findAll();
		Map<String, Integer> arrivePoints = arrivePoints(planets);

		return planets.stream()
			.sorted((left, right) -> {
				int byGroup = left.getPlanetGroup().compareTo(right.getPlanetGroup());
				return byGroup != 0 ? byGroup : Integer.compare(left.getSortOrder(), right.getSortOrder());
			})
			.map(planet -> AdminWorldPlanetResponse.of(planet, arrivePoints.get(planet.getCode())))
			.toList();
	}

	public String create(AdminWorldPlanetCreateRequest request) {
		String code = request.code().strip();
		if (worldPlanetRepository.existsById(code)) {
			throw new WorldException(WorldErrorCode.WORLD_PLANET_CODE_CONFLICT);
		}
		String unlockAfter = normalize(request.unlockAfter());
		validateUnlockAfter(code, unlockAfter);

		worldPlanetRepository.save(WorldPlanet.create(
			code,
			request.name().strip(),
			request.group(),
			request.sortOrder(),
			request.span(),
			unlockAfter,
			Boolean.TRUE.equals(request.selectable()),
			request.rouletteSize() == null ? 1 : request.rouletteSize(),
			request.adsPerWake() == null ? 2 : request.adsPerWake(),
			!Boolean.FALSE.equals(request.referralOk()),
			normalize(request.imageKey())));
		worldCatalogVersionRaiser.raise();
		return code;
	}

	public void update(String code, AdminWorldPlanetUpdateRequest request) {
		WorldPlanet planet = worldPlanetRepository.findById(code)
			.orElseThrow(() -> new WorldException(WorldErrorCode.WORLD_PLANET_NOT_FOUND));

		String unlockAfter = normalize(request.unlockAfter());
		if (unlockAfter != null) {
			validateUnlockAfter(code, unlockAfter);
		}

		planet.update(
			normalize(request.name()),
			request.sortOrder(),
			request.span(),
			unlockAfter,
			request.selectable(),
			request.rouletteSize(),
			request.adsPerWake(),
			request.referralOk(),
			normalize(request.imageKey()),
			request.active());
		worldCatalogVersionRaiser.raise();
	}

	/**
	 * 앞 행성이 실제로 있는지, 그리고 사슬이 자기에게 되돌아오지 않는지. DB 는 자기 참조 FK 라
	 * "있는 행성인가"까지만 막고 순환은 못 막는다. 순환이 생기면 여정이 끊긴다.
	 */
	private void validateUnlockAfter(String code, String unlockAfter) {
		if (unlockAfter.equals(code)) {
			throw new WorldException(WorldErrorCode.WORLD_PLANET_INVALID);
		}
		Map<String, String> previousOf = new HashMap<>();
		worldPlanetRepository.findAll()
			.forEach(planet -> previousOf.put(planet.getCode(), planet.getUnlockAfter()));
		previousOf.put(code, unlockAfter);

		Set<String> visited = new HashSet<>();
		String cursor = code;
		while (cursor != null) {
			if (!visited.add(cursor)) {
				throw new WorldException(WorldErrorCode.WORLD_PLANET_INVALID);
			}
			if (!previousOf.containsKey(cursor)) {
				throw new WorldException(WorldErrorCode.WORLD_PLANET_NOT_FOUND);
			}
			cursor = previousOf.get(cursor);
		}
	}

	/** 여정 순서대로 span 을 누적한 도착점. 어드민이 span 을 고칠 때 결과를 바로 보라고 함께 내린다. */
	private Map<String, Integer> arrivePoints(List<WorldPlanet> planets) {
		Map<String, WorldPlanet> nextOf = new HashMap<>();
		planets.stream()
			.filter(planet -> planet.isActive() && planet.getUnlockAfter() != null)
			.forEach(planet -> nextOf.putIfAbsent(planet.getUnlockAfter(), planet));

		Map<String, Integer> arriveAt = new HashMap<>();
		List<WorldPlanet> roots = planets.stream()
			.filter(planet -> planet.isActive() && planet.getUnlockAfter() == null)
			.toList();

		for (WorldPlanet root : roots) {
			List<String> walked = new ArrayList<>();
			WorldPlanet cursor = root;
			int accumulated = 0;
			while (cursor != null && !walked.contains(cursor.getCode())) {
				walked.add(cursor.getCode());
				arriveAt.put(cursor.getCode(), accumulated);
				accumulated += cursor.getSpan();
				cursor = nextOf.get(cursor.getCode());
			}
		}
		return arriveAt;
	}

	private String normalize(String value) {
		if (value == null) {
			return null;
		}
		String stripped = value.strip();
		return stripped.isEmpty() ? null : stripped;
	}
}

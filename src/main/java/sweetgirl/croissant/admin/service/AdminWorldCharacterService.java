package sweetgirl.croissant.admin.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.world.code.WorldErrorCode;
import sweetgirl.croissant.world.dto.request.AdminWorldCharacterCreateRequest;
import sweetgirl.croissant.world.dto.request.AdminWorldCharacterUpdateRequest;
import sweetgirl.croissant.world.dto.response.AdminWorldCharacterResponse;
import sweetgirl.croissant.world.entity.WorldCharacter;
import sweetgirl.croissant.world.entity.WorldCharacterAsset;
import sweetgirl.croissant.world.exception.WorldException;
import sweetgirl.croissant.world.repository.MemberCharacterRepository;
import sweetgirl.croissant.world.repository.WorldCharacterAssetRepository;
import sweetgirl.croissant.world.repository.WorldCharacterRepository;
import sweetgirl.croissant.world.repository.WorldPlanetRepository;
import sweetgirl.croissant.world.service.WorldCatalogVersionRaiser;

/**
 * 캐릭터를 만들고 고친다. 이름의 정본이 여기라, 출시 전 이름 교체도 앱 배포 없이 끝난다.
 *
 * <p><b>마스코트는 반드시 한 명이어야 한다.</b> DB 의 부분 유니크 인덱스는 "둘"만 막고 "0"은 못 막는데,
 * 0 이 되면 그 뒤 처음 들어오는 모든 회원이 줄 친구를 못 찾아 홈을 못 연다. 그래서 여기서 막는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AdminWorldCharacterService {

	private final WorldCharacterRepository worldCharacterRepository;
	private final WorldCharacterAssetRepository worldCharacterAssetRepository;
	private final WorldPlanetRepository worldPlanetRepository;
	private final MemberCharacterRepository memberCharacterRepository;
	private final WorldCatalogVersionRaiser worldCatalogVersionRaiser;

	@Transactional(readOnly = true)
	public List<AdminWorldCharacterResponse> getCharacters() {
		Map<String, Map<String, String>> assets = assetsByCharacter();

		return worldCharacterRepository.findAll().stream()
			.sorted((left, right) -> {
				int byPlanet = left.getPlanetCode().compareTo(right.getPlanetCode());
				return byPlanet != 0 ? byPlanet : Integer.compare(left.getSortOrder(), right.getSortOrder());
			})
			.map(character -> AdminWorldCharacterResponse.of(
				character,
				assets.getOrDefault(character.getCode(), Map.of()),
				memberCharacterRepository.countByIdCharacterCode(character.getCode())))
			.toList();
	}

	public String create(AdminWorldCharacterCreateRequest request) {
		String code = request.code().strip();
		if (worldCharacterRepository.existsById(code)) {
			throw new WorldException(WorldErrorCode.WORLD_CHARACTER_CODE_CONFLICT);
		}
		requirePlanet(request.planetCode());

		boolean mascot = Boolean.TRUE.equals(request.mascot());
		if (mascot) {
			worldCharacterRepository.clearMascot();
		}
		worldCharacterRepository.save(WorldCharacter.create(
			code, request.name().strip(), request.planetCode().strip(), request.sortOrder(), mascot));
		worldCatalogVersionRaiser.raise();
		return code;
	}

	public void update(String code, AdminWorldCharacterUpdateRequest request) {
		WorldCharacter character = worldCharacterRepository.findById(code)
			.orElseThrow(() -> new WorldException(WorldErrorCode.WORLD_CHARACTER_NOT_FOUND));
		if (request.planetCode() != null) {
			requirePlanet(request.planetCode());
		}
		validateMascotSurvives(character, request);

		if (Boolean.TRUE.equals(request.mascot()) && !character.isMascot()) {
			worldCharacterRepository.clearMascot();
		}
		character.update(
			normalize(request.name()),
			normalize(request.planetCode()),
			request.sortOrder(),
			request.mascot(),
			request.active());
		worldCatalogVersionRaiser.raise();
	}

	/**
	 * 지금 마스코트를 내리거나 비활성으로 돌리려 하면 막는다 — 대신 세울 사람을 함께 지정해야 한다.
	 * 빈자리로 두면 신규 회원의 첫 조회가 통째로 실패한다.
	 */
	private void validateMascotSurvives(
			WorldCharacter character,
			AdminWorldCharacterUpdateRequest request) {
		if (!character.isMascot()) {
			return;
		}
		boolean droppingMascot = Boolean.FALSE.equals(request.mascot());
		boolean deactivating = Boolean.FALSE.equals(request.active());
		if (droppingMascot || deactivating) {
			throw new WorldException(WorldErrorCode.WORLD_MASCOT_REQUIRED);
		}
	}

	private Map<String, Map<String, String>> assetsByCharacter() {
		Map<String, Map<String, String>> byCharacter = new LinkedHashMap<>();
		for (WorldCharacterAsset asset : worldCharacterAssetRepository
			.findAllByOrderByIdCharacterCodeAscIdPoseAsc()) {
			byCharacter
				.computeIfAbsent(asset.characterCode(), characterCode -> new LinkedHashMap<>())
				.put(asset.pose(), asset.getImageKey());
		}
		return byCharacter;
	}

	private void requirePlanet(String planetCode) {
		if (!worldPlanetRepository.existsById(planetCode.strip())) {
			throw new WorldException(WorldErrorCode.WORLD_PLANET_NOT_FOUND);
		}
	}

	private String normalize(String value) {
		if (value == null) {
			return null;
		}
		String stripped = value.strip();
		return stripped.isEmpty() ? null : stripped;
	}
}

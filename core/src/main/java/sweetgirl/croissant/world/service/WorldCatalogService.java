package sweetgirl.croissant.world.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.world.code.WorldErrorCode;
import sweetgirl.croissant.world.dto.response.WorldCatalogResponse;
import sweetgirl.croissant.world.dto.response.WorldCharacterResponse;
import sweetgirl.croissant.world.dto.response.WorldPlanetResponse;
import sweetgirl.croissant.world.dto.response.WorldStageResponse;
import sweetgirl.croissant.world.entity.WorldCharacter;
import sweetgirl.croissant.world.entity.WorldCharacterAsset;
import sweetgirl.croissant.world.entity.WorldPlanet;
import sweetgirl.croissant.world.entity.WorldPlanetStage;
import sweetgirl.croissant.world.exception.WorldException;
import sweetgirl.croissant.world.repository.WorldCatalogVersionRepository;
import sweetgirl.croissant.world.repository.WorldCharacterAssetRepository;
import sweetgirl.croissant.world.repository.WorldCharacterRepository;
import sweetgirl.croissant.world.repository.WorldPlanetRepository;
import sweetgirl.croissant.world.repository.WorldPlanetStageRepository;

/**
 * 전역 카탈로그를 읽어 응답 모양으로 만들어 들고 있는다.
 *
 * <p>카탈로그는 회원과 무관하고 거의 바뀌지 않는데 홈 진입마다 필요하다. 그래서 만들어진 응답을
 * 통째로 들고 있다가 그대로 돌려준다. 다만 <b>버전 숫자 한 칸은 매번 읽는다</b> — 인스턴스가
 * 둘 이상일 때(blue-green 배포 중 등) 어드민이 다른 인스턴스에서 저장한 변경을 이쪽도 알아야
 * 하기 때문이다. 캐시가 막는 건 본문 쿼리 네 방이고, 남는 건 PK 단일 행 조회 하나다.
 *
 * <p>스프링 캐시 추상화를 쓰지 않았다. 지금 이 프로젝트에는 캐시 매니저가 없고, 무효화 기준이
 * 시간이 아니라 버전 숫자라서 {@code @Cacheable} 의 TTL 모델과 맞지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorldCatalogService {

	private final WorldPlanetRepository worldPlanetRepository;
	private final WorldPlanetStageRepository worldPlanetStageRepository;
	private final WorldCharacterRepository worldCharacterRepository;
	private final WorldCharacterAssetRepository worldCharacterAssetRepository;
	private final WorldCatalogVersionRepository worldCatalogVersionRepository;

	/** 뱃지와 같은 설정 키를 쓴다. 도메인이 바뀌어도 DB 의 image_key 는 그대로 둔다. */
	@Value("${app.cdn-base-url:https://cdn.bbang-ggut.cloud}")
	private String cdnBaseUrl;

	private final AtomicReference<WorldCatalogResponse> cachedCatalog = new AtomicReference<>();

	public WorldCatalogResponse getCatalog() {
		int version = currentVersion();
		WorldCatalogResponse cached = cachedCatalog.get();
		if (cached != null && cached.version() == version) {
			return cached;
		}

		WorldCatalogResponse loaded = load(version);
		cachedCatalog.set(loaded);
		return loaded;
	}

	public int currentVersion() {
		return worldCatalogVersionRepository.findCurrentVersion()
			.orElseThrow(() -> new WorldException(WorldErrorCode.WORLD_CATALOG_NOT_INITIALIZED));
	}

	private WorldCatalogResponse load(int version) {
		return new WorldCatalogResponse(version, loadPlanets(), loadCharacters());
	}

	private List<WorldPlanetResponse> loadPlanets() {
		Map<String, List<WorldStageResponse>> stagesByPlanet = new LinkedHashMap<>();
		for (WorldPlanetStage stage : worldPlanetStageRepository
			.findAllByOrderByPlanetCodeAscStageOrderAsc()) {
			stagesByPlanet
				.computeIfAbsent(stage.getPlanetCode(), planetCode -> new ArrayList<>())
				.add(WorldStageResponse.of(stage, toImageUrl(stage.getImageKey())));
		}

		List<WorldPlanetResponse> planets = new ArrayList<>();
		for (WorldPlanet planet : worldPlanetRepository.findAllByActiveTrueOrderBySortOrderAscCodeAsc()) {
			planets.add(WorldPlanetResponse.of(
				planet,
				toImageUrl(planet.getImageKey()),
				stagesByPlanet.getOrDefault(planet.getCode(), List.of())));
		}
		return planets;
	}

	private List<WorldCharacterResponse> loadCharacters() {
		Map<String, Map<String, String>> assetsByCharacter = new LinkedHashMap<>();
		for (WorldCharacterAsset asset : worldCharacterAssetRepository
			.findAllByOrderByIdCharacterCodeAscIdPoseAsc()) {
			String imageUrl = toImageUrl(asset.getImageKey());
			if (imageUrl == null) {
				continue;
			}
			assetsByCharacter
				.computeIfAbsent(asset.characterCode(), characterCode -> new LinkedHashMap<>())
				.put(asset.pose(), imageUrl);
		}

		return worldCharacterRepository.findAllByActiveTrueOrderByPlanetCodeAscSortOrderAsc().stream()
			.map(character -> toCharacterResponse(character, assetsByCharacter))
			.toList();
	}

	private WorldCharacterResponse toCharacterResponse(
			WorldCharacter character,
			Map<String, Map<String, String>> assetsByCharacter) {
		return WorldCharacterResponse.of(
			character,
			assetsByCharacter.getOrDefault(character.getCode(), Map.of()));
	}

	/**
	 * DB 는 키만 갖고 도메인은 서버가 붙인다 — CDN 도메인이 바뀌어도 DB 를 고치지 않기 위해서다.
	 * 키가 없으면 URL 도 없고, 앱은 번들 그림으로 돌아간다.
	 */
	private String toImageUrl(String imageKey) {
		if (imageKey == null || imageKey.isBlank()) {
			return null;
		}
		if (imageKey.startsWith("http://") || imageKey.startsWith("https://")) {
			return imageKey;
		}
		return cdnBaseUrl.replaceAll("/+$", "") + "/" + imageKey.replaceAll("^/+", "");
	}
}

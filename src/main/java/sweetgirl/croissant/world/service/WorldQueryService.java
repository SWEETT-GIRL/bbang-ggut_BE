package sweetgirl.croissant.world.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.reward.service.RankingRewardService;
import sweetgirl.croissant.world.code.WorldErrorCode;
import sweetgirl.croissant.world.dto.response.MemberCharacterResponse;
import sweetgirl.croissant.world.dto.response.MemberWorldResponse;
import sweetgirl.croissant.world.dto.response.WorldCatalogResponse;
import sweetgirl.croissant.world.dto.response.WorldCharacterResponse;
import sweetgirl.croissant.world.dto.response.WorldPlanetResponse;
import sweetgirl.croissant.world.entity.MemberCharacter;
import sweetgirl.croissant.world.entity.MemberPlanetArrival;
import sweetgirl.croissant.world.entity.MemberWorld;
import sweetgirl.croissant.world.exception.WorldException;
import sweetgirl.croissant.world.repository.MemberCharacterRepository;
import sweetgirl.croissant.world.repository.MemberPlanetArrivalRepository;
import sweetgirl.croissant.world.repository.MemberWakeTicketRepository;
import sweetgirl.croissant.world.repository.MemberWorldRepository;

/**
 * 홈이 들어올 때마다 읽는 개인 상태를 모은다. 카탈로그는 여기서 읽지 않고 캐시된 것을 빌려 쓴다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorldQueryService {

	private final MemberWorldRepository memberWorldRepository;
	private final MemberCharacterRepository memberCharacterRepository;
	private final MemberPlanetArrivalRepository memberPlanetArrivalRepository;
	private final MemberWakeTicketRepository memberWakeTicketRepository;
	private final BreadRecordRepository breadRecordRepository;
	private final WorldCatalogService worldCatalogService;
	private final WorldProgressCalculator worldProgressCalculator;
	private final RankingRewardService rankingRewardService;

	public MemberWorldResponse getMemberWorld(Long memberId) {
		MemberWorld memberWorld = memberWorldRepository.findById(memberId)
			.orElseThrow(() -> new WorldException(WorldErrorCode.WORLD_NOT_STARTED));
		WorldCatalogResponse catalog = worldCatalogService.getCatalog();

		long recordCount = breadRecordRepository.countByMemberIdAndDeletedAtIsNull(memberId);
		int reachedHeight = (int)Math.max(memberWorld.getMaxRecordCount(), recordCount);

		List<MemberPlanetArrival> arrivals = memberPlanetArrivalRepository.findAllByIdMemberId(memberId);
		WorldProgress progress = worldProgressCalculator.calculate(
			catalog.planets(),
			reachedHeight,
			arrivals.stream().map(MemberPlanetArrival::planetCode).toList());

		WorldLevel level = worldProgressCalculator.level(
			catalog.planets(),
			reachedHeight,
			arrivals.stream().map(MemberPlanetArrival::planetCode).toList());

		return new MemberWorldResponse(
			catalog.version(),
			recordCount,
			reachedHeight,
			progress.planet(),
			progress.stage(),
			progress.nextPlanet(),
			progress.toNext(),
			progress.ratio(),
			memberWorld.getSelectedCharacter(),
			pendingRoulettePlanets(catalog, arrivals),
			memberWakeTicketRepository.countByMemberIdAndUsedAtIsNull(memberId),
			characters(catalog, memberId),
			memberWorld.getWelcomedAt(),
			level.level(),
			level.levelUpAt(),
			rankingRewardService.crownedMemberIds().contains(memberId),
			level.levelStartAt());
	}

	/**
	 * 닿았는데 아직 룰렛을 안 돌린 행성. 룰렛 인원이 0 인 행성(첫 친구를 마스코트로 주는 지구)은
	 * 돌릴 것이 없으므로 빠진다.
	 */
	/**
	 * 닿았는데 아직 룰렛을 안 돌린 행성들. 세계관을 시작하지 않은 회원은 비어 있다.
	 * 홈의 할 일 카드가 읽는다 — 진행도를 다시 맞추지는 않고 남아 있는 도착 이력만 본다.
	 */
	public List<String> pendingRoulettePlanets(Long memberId) {
		return pendingRoulettePlanets(
			worldCatalogService.getCatalog(), memberPlanetArrivalRepository.findAllByIdMemberId(memberId));
	}

	private List<String> pendingRoulettePlanets(
			WorldCatalogResponse catalog,
			List<MemberPlanetArrival> arrivals) {
		Map<String, WorldPlanetResponse> planets = catalog.planets().stream()
			.collect(Collectors.toMap(WorldPlanetResponse::code, Function.identity()));

		return arrivals.stream()
			.filter(arrival -> !arrival.hasSpunRoulette())
			.filter(arrival -> {
				WorldPlanetResponse planet = planets.get(arrival.planetCode());
				return planet != null && planet.rouletteSize() > 0;
			})
			.map(MemberPlanetArrival::planetCode)
			// 여정 순서 — 코드 글자순이면 목성(jupiter)이 화성(mars)보다 먼저 나온다
			.sorted(Comparator.comparingInt(code -> planets.get(code).sortOrder()))
			.toList();
	}

	private List<MemberCharacterResponse> characters(WorldCatalogResponse catalog, Long memberId) {
		Map<String, Integer> adsPerWake = adsPerWakeByCharacter(catalog);

		return memberCharacterRepository.findAllByIdMemberId(memberId).stream()
			.sorted(Comparator.comparing(MemberCharacter::characterCode))
			.map(memberCharacter -> MemberCharacterResponse.of(
				memberCharacter,
				adsPerWake.getOrDefault(memberCharacter.characterCode(), 0)))
			.toList();
	}

	/** 광고 편수는 상수가 아니라 그 친구가 사는 행성 행의 값이다. */
	private Map<String, Integer> adsPerWakeByCharacter(WorldCatalogResponse catalog) {
		Map<String, Integer> adsPerWakeByPlanet = catalog.planets().stream()
			.collect(Collectors.toMap(WorldPlanetResponse::code, WorldPlanetResponse::adsPerWake));

		return catalog.characters().stream()
			.filter(character -> adsPerWakeByPlanet.containsKey(character.planet()))
			.collect(Collectors.toMap(
				WorldCharacterResponse::code,
				character -> adsPerWakeByPlanet.get(character.planet())));
	}
}

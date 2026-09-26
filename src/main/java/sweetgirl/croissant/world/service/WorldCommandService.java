package sweetgirl.croissant.world.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.world.code.WorldErrorCode;
import sweetgirl.croissant.world.dto.response.WorldCharacterResponse;
import sweetgirl.croissant.world.dto.response.WorldPlanetResponse;
import sweetgirl.croissant.world.dto.response.WorldRouletteResponse;
import sweetgirl.croissant.world.dto.response.WorldWakeResponse;
import sweetgirl.croissant.world.entity.MemberCharacter;
import sweetgirl.croissant.world.entity.MemberCharacterId;
import sweetgirl.croissant.world.entity.MemberPlanetArrival;
import sweetgirl.croissant.world.entity.MemberWakeTicket;
import sweetgirl.croissant.world.entity.MemberWorld;
import sweetgirl.croissant.world.entity.WorldCharacter;
import sweetgirl.croissant.world.enums.AcquiredBy;
import sweetgirl.croissant.world.exception.WorldException;
import sweetgirl.croissant.world.repository.MemberCharacterRepository;
import sweetgirl.croissant.world.repository.MemberPlanetArrivalRepository;
import sweetgirl.croissant.world.repository.MemberWakeTicketRepository;
import sweetgirl.croissant.world.repository.MemberWorldRepository;
import sweetgirl.croissant.world.repository.WorldCharacterRepository;

/**
 * 개인 상태를 읽기 전에 밀린 것을 따라잡는다. 홈 진입 때마다 불리므로 보통은 아무것도 쓰지 않는다.
 *
 * <p>하는 일 둘.
 * <ul>
 *   <li><b>여정 시작</b> — 가입 경로가 넷(애플 · 카카오 · 게스트 · 게스트 승격)이라 가입 훅을 네 군데
 *       달지 않고 첫 조회에서 만든다.</li>
 *   <li><b>도착 이력</b> — 닿은 행성을 남긴다. 이 이력이 있어야 나중에 임계값을 올려도 이미 도착한
 *       회원이 뒤로 밀리지 않는다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class WorldCommandService {

	private final MemberWorldRepository memberWorldRepository;
	private final MemberCharacterRepository memberCharacterRepository;
	private final MemberPlanetArrivalRepository memberPlanetArrivalRepository;
	private final MemberWakeTicketRepository memberWakeTicketRepository;
	private final WorldCharacterRepository worldCharacterRepository;
	private final BreadRecordRepository breadRecordRepository;
	private final WorldCatalogService worldCatalogService;
	private final WorldProgressCalculator worldProgressCalculator;

	public void syncProgress(Long memberId) {
		startIfAbsent(memberId);
		recordArrivals(memberId);
	}

	/** 데리고 다닐 친구를 바꾼다. 아직 못 만난 친구는 고를 수 없다. */
	public void selectCharacter(Long memberId, String characterCode) {
		MemberWorld memberWorld = memberWorldRepository.findById(memberId)
			.orElseThrow(() -> new WorldException(WorldErrorCode.WORLD_NOT_STARTED));

		MemberCharacter memberCharacter = memberCharacterRepository
			.findById(new MemberCharacterId(memberId, characterCode))
			.orElseThrow(() -> new WorldException(WorldErrorCode.WORLD_CHARACTER_NOT_FOUND));
		if (!memberCharacter.isAwake()) {
			throw new WorldException(WorldErrorCode.WORLD_CHARACTER_LOCKED);
		}

		memberWorld.selectCharacter(characterCode);
	}

	/**
	 * 행성 도착 룰렛. <b>뽑기는 서버가 한다</b> — 앱이 뽑으면 결과를 앱이 정하게 된다.
	 *
	 * <p>검사 순서에 뜻이 있다. 후보가 남았는지를 <b>돌린 것으로 표시하기 전에</b> 본다. 순서를
	 * 뒤집으면 줄 친구가 없는 호출이 룰렛만 태워 버린다.
	 */
	public WorldRouletteResponse spinRoulette(Long memberId, String planetCode) {
		WorldPlanetResponse planet = worldCatalogService.getCatalog().planets().stream()
			.filter(candidate -> candidate.code().equals(planetCode))
			.findFirst()
			.orElseThrow(() -> new WorldException(WorldErrorCode.WORLD_PLANET_NOT_FOUND));
		if (planet.rouletteSize() <= 0) {
			throw new WorldException(WorldErrorCode.WORLD_ROULETTE_NOT_AVAILABLE);
		}
		requireArrived(memberId, planetCode);

		List<String> candidates = sleepingCandidates(memberId, planetCode);
		if (candidates.isEmpty()) {
			throw new WorldException(WorldErrorCode.WORLD_NO_SLEEPING_FRIEND);
		}

		if (memberPlanetArrivalRepository.markRouletteSpun(memberId, planetCode) == 0) {
			throw new WorldException(WorldErrorCode.WORLD_ROULETTE_ALREADY_DONE);
		}

		Collections.shuffle(candidates);
		List<String> awakened = candidates.subList(0, Math.min(planet.rouletteSize(), candidates.size()));
		awakened.forEach(characterCode -> memberCharacterRepository
			.grantIfAbsent(memberId, characterCode, AcquiredBy.ROULETTE.name()));
		return new WorldRouletteResponse(List.copyOf(awakened));
	}

	private void requireArrived(Long memberId, String planetCode) {
		boolean arrived = memberPlanetArrivalRepository.findAllByIdMemberId(memberId).stream()
			.anyMatch(arrival -> arrival.planetCode().equals(planetCode));
		if (!arrived) {
			throw new WorldException(WorldErrorCode.WORLD_PLANET_NOT_REACHED);
		}
	}

	/** 그 행성에 사는 캐릭터 중 아직 깨어나지 않은 것. 카탈로그에서 빠진 캐릭터는 후보가 아니다. */
	private List<String> sleepingCandidates(Long memberId, String planetCode) {
		Set<String> awake = memberCharacterRepository.findAllByIdMemberId(memberId).stream()
			.filter(MemberCharacter::isAwake)
			.map(MemberCharacter::characterCode)
			.collect(Collectors.toSet());

		return new ArrayList<>(worldCatalogService.getCatalog().characters().stream()
			.filter(character -> character.planet().equals(planetCode))
			.map(character -> character.code())
			.filter(characterCode -> !awake.contains(characterCode))
			.toList());
	}

	/**
	 * 추천으로 받은 티켓을 써서 광고를 건너뛰고 한 명을 바로 깨운다.
	 *
	 * <p>이미 깨어난 친구면 티켓을 쓰기 전에 막는다. 나란히 들어와 둘 다 검사를 지나면, 지급도 깨우기도
	 * 0 행인 쪽이 예외를 던져 티켓 사용까지 되돌린다 — 티켓은 누군가를 깨웠을 때만 줄어든다.
	 */
	public WorldWakeResponse useWakeTicket(Long memberId, String characterCode) {
		WorldPlanetResponse planet = requireWakeable(memberId, characterCode);
		if (!planet.referralOk()) {
			throw new WorldException(WorldErrorCode.WORLD_REFERRAL_NOT_ALLOWED);
		}
		boolean alreadyAwake = memberCharacterRepository
			.findById(new MemberCharacterId(memberId, characterCode))
			.map(MemberCharacter::isAwake)
			.orElse(false);
		if (alreadyAwake) {
			throw new WorldException(WorldErrorCode.WORLD_CHARACTER_ALREADY_AWAKE);
		}

		MemberWakeTicket ticket = memberWakeTicketRepository
			.findFirstByMemberIdAndUsedAtIsNullOrderByIdAsc(memberId)
			.orElseThrow(() -> new WorldException(WorldErrorCode.WORLD_NO_WAKE_TICKET));
		if (memberWakeTicketRepository.useTicket(ticket.getId()) == 0) {
			throw new WorldException(WorldErrorCode.WORLD_NO_WAKE_TICKET);
		}

		// 획득 경로는 늘 REFERRAL 이다 — 티켓이 어디서 났는지는 티켓 행이 갖고 있다.
		int granted = memberCharacterRepository.grantIfAbsent(memberId, characterCode, AcquiredBy.REFERRAL.name());
		int awakened = memberCharacterRepository.awakeIfSleeping(memberId, characterCode, AcquiredBy.REFERRAL.name());
		if (granted == 0 && awakened == 0) {
			throw new WorldException(WorldErrorCode.WORLD_CHARACTER_ALREADY_AWAKE);
		}
		return new WorldWakeResponse(characterCode, 0, planet.adsPerWake(), true);
	}

	/**
	 * 깨우기 공통 조건 — 그 친구가 사는 행성에 닿았는가, 그 행성의 룰렛을 돌렸는가.
	 *
	 * <p>룰렛을 먼저 돌리게 하는 이유: 안 그러면 광고로 넷을 다 모은 뒤 룰렛을 돌려 "줄 친구가
	 * 없는" 구멍이 생긴다. 룰렛이 없는 행성(지구)은 이 조건을 건너뛴다.
	 */
	public WorldPlanetResponse requireWakeable(Long memberId, String characterCode) {
		WorldCharacterResponse character = worldCatalogService.getCatalog().characters().stream()
			.filter(candidate -> candidate.code().equals(characterCode))
			.findFirst()
			.orElseThrow(() -> new WorldException(WorldErrorCode.WORLD_CHARACTER_NOT_FOUND));

		WorldPlanetResponse planet = worldCatalogService.getCatalog().planets().stream()
			.filter(candidate -> candidate.code().equals(character.planet()))
			.findFirst()
			.orElseThrow(() -> new WorldException(WorldErrorCode.WORLD_PLANET_NOT_FOUND));

		MemberPlanetArrival arrival = memberPlanetArrivalRepository.findAllByIdMemberId(memberId)
			.stream()
			.filter(candidate -> candidate.planetCode().equals(planet.code()))
			.findFirst()
			.orElseThrow(() -> new WorldException(WorldErrorCode.WORLD_PLANET_NOT_REACHED));

		if (planet.rouletteSize() > 0 && !arrival.hasSpunRoulette()) {
			throw new WorldException(WorldErrorCode.WORLD_ROULETTE_REQUIRED);
		}
		return planet;
	}

	/** 첫 친구 연출을 봤다고 표시한다. 두 번 불러도 처음 본 때가 남는다. */
	public void welcome(Long memberId) {
		memberWorldRepository.findById(memberId)
			.orElseThrow(() -> new WorldException(WorldErrorCode.WORLD_NOT_STARTED))
			.welcome(LocalDateTime.now());
	}

	private void startIfAbsent(Long memberId) {
		if (memberWorldRepository.existsById(memberId)) {
			return;
		}

		WorldCharacter mascot = worldCharacterRepository.findByMascotTrue()
			.orElseThrow(() -> new WorldException(WorldErrorCode.WORLD_MASCOT_NOT_CONFIGURED));
		memberWorldRepository.startIfAbsent(memberId, mascot.getCode());
		memberCharacterRepository.grantIfAbsent(memberId, mascot.getCode(), AcquiredBy.FIRST.name());
	}

	private void recordArrivals(Long memberId) {
		MemberWorld memberWorld = memberWorldRepository.findById(memberId)
			.orElseThrow(() -> new WorldException(WorldErrorCode.WORLD_NOT_STARTED));
		int reachedHeight = reachedHeight(memberId, memberWorld);

		Set<String> already = memberPlanetArrivalRepository.findAllByIdMemberId(memberId).stream()
			.map(MemberPlanetArrival::planetCode)
			.collect(Collectors.toSet());

		List<String> reached = worldProgressCalculator
			.reachedPlanetCodes(worldCatalogService.getCatalog().planets(), reachedHeight);
		reached.stream()
			.filter(planetCode -> !already.contains(planetCode))
			.forEach(planetCode -> memberPlanetArrivalRepository.arriveIfAbsent(memberId, planetCode));
	}

	/**
	 * 진행도의 기준이 되는 높이. 저장해 둔 최고 높이와 지금 기록 수 중 큰 쪽이다.
	 *
	 * <p>지금 기록 수를 함께 보는 이유: 최고 높이를 갱신하는 쪽은 기록 저장 경로인데, 그 연결이
	 * 붙기 전의 회원은 저장된 값이 0 이다. 큰 쪽을 쓰면 옛 회원도 첫 조회에서 제 높이를 찾는다.
	 */
	private int reachedHeight(Long memberId, MemberWorld memberWorld) {
		long recordCount = breadRecordRepository.countByMemberIdAndDeletedAtIsNull(memberId);
		return (int)Math.max(memberWorld.getMaxRecordCount(), recordCount);
	}
}

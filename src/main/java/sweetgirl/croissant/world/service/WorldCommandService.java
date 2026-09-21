package sweetgirl.croissant.world.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.world.code.WorldErrorCode;
import sweetgirl.croissant.world.entity.MemberCharacter;
import sweetgirl.croissant.world.entity.MemberCharacterId;
import sweetgirl.croissant.world.entity.MemberPlanetArrival;
import sweetgirl.croissant.world.entity.MemberWorld;
import sweetgirl.croissant.world.entity.WorldCharacter;
import sweetgirl.croissant.world.enums.AcquiredBy;
import sweetgirl.croissant.world.exception.WorldException;
import sweetgirl.croissant.world.repository.MemberCharacterRepository;
import sweetgirl.croissant.world.repository.MemberPlanetArrivalRepository;
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

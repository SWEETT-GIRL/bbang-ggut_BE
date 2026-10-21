package sweetgirl.croissant.admin.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.world.code.WorldErrorCode;
import sweetgirl.croissant.world.dto.request.AdminWorldMemberGrantRequest;
import sweetgirl.croissant.world.dto.response.AdminWorldMemberResponse;
import sweetgirl.croissant.world.dto.response.MemberCharacterResponse;
import sweetgirl.croissant.world.dto.response.WorldPlanetResponse;
import sweetgirl.croissant.world.entity.MemberPlanetArrival;
import sweetgirl.croissant.world.entity.MemberWakeTicket;
import sweetgirl.croissant.world.entity.MemberWorld;
import sweetgirl.croissant.world.enums.AcquiredBy;
import sweetgirl.croissant.world.enums.WakeTicketSource;
import sweetgirl.croissant.world.exception.WorldException;
import sweetgirl.croissant.world.repository.MemberCharacterRepository;
import sweetgirl.croissant.world.repository.MemberPlanetArrivalRepository;
import sweetgirl.croissant.world.repository.MemberWakeTicketRepository;
import sweetgirl.croissant.world.repository.MemberWorldRepository;
import sweetgirl.croissant.world.repository.WorldCharacterRepository;
import sweetgirl.croissant.world.service.WorldCatalogService;

/**
 * 한 회원의 세계관 상태를 들여다보고 손봐 준다. CS 와 광고 오류 보상이 쓰는 자리다.
 *
 * <p>여기서는 카탈로그 번호를 올리지 않는다 — 회원 상태는 카탈로그가 아니라서, 올리면 애먼 회원들이
 * 카탈로그를 통째로 다시 받는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AdminWorldMemberService {

	private final MemberWorldRepository memberWorldRepository;
	private final MemberCharacterRepository memberCharacterRepository;
	private final MemberPlanetArrivalRepository memberPlanetArrivalRepository;
	private final MemberWakeTicketRepository memberWakeTicketRepository;
	private final WorldCharacterRepository worldCharacterRepository;
	private final BreadRecordRepository breadRecordRepository;
	private final WorldCatalogService worldCatalogService;

	@Transactional(readOnly = true)
	public AdminWorldMemberResponse getMemberWorld(Long memberId) {
		MemberWorld memberWorld = memberWorldRepository.findById(memberId)
			.orElseThrow(() -> new WorldException(WorldErrorCode.WORLD_NOT_STARTED));
		Map<String, Integer> adsPerWake = adsPerWakeByCharacter();

		return new AdminWorldMemberResponse(
			memberId,
			memberWorld.getSelectedCharacter(),
			memberWorld.getMaxRecordCount(),
			breadRecordRepository.countByMemberIdAndDeletedAtIsNull(memberId),
			memberWorld.getWelcomedAt(),
			memberWakeTicketRepository.countByMemberIdAndUsedAtIsNull(memberId),
			memberCharacterRepository.findAllByIdMemberId(memberId).stream()
				.map(memberCharacter -> MemberCharacterResponse.of(
					memberCharacter,
					adsPerWake.getOrDefault(memberCharacter.characterCode(), 0)))
				.toList(),
			memberPlanetArrivalRepository.findAllByIdMemberId(memberId).stream()
				.map(arrival -> new AdminWorldMemberResponse.Arrival(
					arrival.planetCode(), arrival.getArrivedAt(), arrival.getRouletteAt()))
				.toList());
	}

	public void grant(Long memberId, AdminWorldMemberGrantRequest request) {
		if (!memberWorldRepository.existsById(memberId)) {
			throw new WorldException(WorldErrorCode.WORLD_NOT_STARTED);
		}

		if (request.characterCode() != null) {
			String characterCode = request.characterCode().strip();
			if (!worldCharacterRepository.existsById(characterCode)) {
				throw new WorldException(WorldErrorCode.WORLD_CHARACTER_NOT_FOUND);
			}
			// 이미 가진 친구면 획득 경로와 시각을 덮지 않는다
			memberCharacterRepository.grantIfAbsent(memberId, characterCode, AcquiredBy.ADMIN.name());
			memberCharacterRepository.awakeIfSleeping(memberId, characterCode, AcquiredBy.ADMIN.name());
		}
		if (request.maxRecordCount() != null) {
			// 캐릭터 지급 쿼리가 영속성 컨텍스트를 비운다 — 그 뒤에 읽어야 진행도 변경이 저장된다
			memberWorldRepository.findById(memberId)
				.orElseThrow(() -> new WorldException(WorldErrorCode.WORLD_NOT_STARTED))
				.adjustMaxRecordCount(request.maxRecordCount());
		}
		if (request.wakeTickets() != null) {
			List<MemberWakeTicket> tickets = IntStream.range(0, request.wakeTickets())
				.mapToObj(index -> MemberWakeTicket.issue(memberId, WakeTicketSource.ADMIN, null))
				.toList();
			memberWakeTicketRepository.saveAll(tickets);
		}
	}

	/** 광고 편수는 그 친구가 사는 행성 값이다. */
	private Map<String, Integer> adsPerWakeByCharacter() {
		Map<String, Integer> byPlanet = worldCatalogService.getCatalog().planets().stream()
			.collect(Collectors.toMap(WorldPlanetResponse::code, WorldPlanetResponse::adsPerWake));

		return worldCatalogService.getCatalog().characters().stream()
			.filter(character -> byPlanet.containsKey(character.planet()))
			.collect(Collectors.toMap(
				character -> character.code(),
				character -> byPlanet.get(character.planet()),
				(first, second) -> first,
				java.util.LinkedHashMap::new));
	}
}

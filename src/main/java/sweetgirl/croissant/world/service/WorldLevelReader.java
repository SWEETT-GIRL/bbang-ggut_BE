package sweetgirl.croissant.world.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.world.entity.MemberPlanetArrival;
import sweetgirl.croissant.world.repository.MemberPlanetArrivalRepository;

/** 회원의 레벨을 읽는다. 기록 저장처럼 세계관 밖에서 "이 높이면 몇 레벨인가"를 물을 때 쓴다. */
@Component
@RequiredArgsConstructor
public class WorldLevelReader {

	private final WorldCatalogService worldCatalogService;
	private final WorldProgressCalculator worldProgressCalculator;
	private final MemberPlanetArrivalRepository memberPlanetArrivalRepository;

	/**
	 * 빵탑이 이 높이일 때의 레벨. 도착 이력이 높이보다 멀리 가 있으면 이력을 따른다.
	 *
	 * @param height 빵탑의 최고 높이(지금까지 가장 많이 쌓았던 기록 수)
	 */
	@Transactional(readOnly = true)
	public WorldLevel levelAt(Long memberId, int height) {
		return worldProgressCalculator.level(
			worldCatalogService.getCatalog().planets(),
			height,
			memberPlanetArrivalRepository.findAllByIdMemberId(memberId).stream()
				.map(MemberPlanetArrival::planetCode)
				.toList());
	}
}

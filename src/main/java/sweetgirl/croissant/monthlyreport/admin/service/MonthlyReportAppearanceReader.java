package sweetgirl.croissant.monthlyreport.admin.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.world.dto.response.WorldCatalogResponse;
import sweetgirl.croissant.world.dto.response.WorldPlanetResponse;
import sweetgirl.croissant.world.entity.MemberPlanetArrival;
import sweetgirl.croissant.world.entity.MemberWorld;
import sweetgirl.croissant.world.entity.WorldCharacter;
import sweetgirl.croissant.world.entity.WorldCharacterAssetId;
import sweetgirl.croissant.world.repository.MemberPlanetArrivalRepository;
import sweetgirl.croissant.world.repository.MemberWorldRepository;
import sweetgirl.croissant.world.repository.WorldCharacterAssetRepository;
import sweetgirl.croissant.world.repository.WorldCharacterRepository;
import sweetgirl.croissant.world.service.WorldCatalogService;
import sweetgirl.croissant.world.service.WorldProgressCalculator;

/** 초안 생성 시점의 세계관을 읽는다. 세계관 미진입 회원도 마스코트와 기존 행성 계산으로 처리한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MonthlyReportAppearanceReader {
	private final MemberReader members;
	private final MemberWorldRepository worlds;
	private final WorldCharacterRepository characters;
	private final WorldCharacterAssetRepository characterAssets;
	private final MemberPlanetArrivalRepository arrivals;
	private final BreadRecordRepository records;
	private final WorldCatalogService catalogService;
	private final WorldProgressCalculator calculator;

	public MonthlyReportResponse draft(Long memberId, String month) {
		MemberWorld world = worlds.findById(memberId).orElse(null);
		WorldCharacter character = world == null ? characters.findByMascotTrue().orElseThrow()
			: characters.findById(world.getSelectedCharacter()).orElseThrow();
		WorldCatalogResponse catalog = catalogService.getCatalog();
		int height = Math.toIntExact(Math.max(world == null ? 0 : world.getMaxRecordCount(),
			records.countByMemberIdAndDeletedAtIsNull(memberId)));
		String planetCode = calculator.calculate(catalog.planets(), height,
			arrivals.findAllByIdMemberId(memberId).stream().map(MemberPlanetArrival::planetCode).toList()).planet();
		WorldPlanetResponse planet = catalog.planets().stream().filter(value -> value.code().equals(planetCode))
			.findFirst().orElseThrow();
		String imageKey = characterAssets.findById(new WorldCharacterAssetId(character.getCode(), "front"))
			.map(asset -> asset.getImageKey()).orElse(null);
		return new MonthlyReportResponse(1, month, memberId, members.getMember(memberId).getNickname(),
			new MonthlyReportResponse.Character(character.getCode(), character.getName(), imageKey),
			new MonthlyReportResponse.Planet(planet.code(), planet.name()),
			new MonthlyReportResponse.Summary(0L, 0L, 0L), new MonthlyReportResponse.Stores(0L, 0L, 0L, 0L),
			List.of(), new MonthlyReportResponse.Activity("seed", null, null, null),
			new MonthlyReportResponse.Comparison(null, 0L), List.of());
	}
}

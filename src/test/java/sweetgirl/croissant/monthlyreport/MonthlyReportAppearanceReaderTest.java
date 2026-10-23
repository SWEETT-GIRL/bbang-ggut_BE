package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAppearanceReader;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.world.dto.response.WorldCatalogResponse;
import sweetgirl.croissant.world.dto.response.WorldPlanetResponse;
import sweetgirl.croissant.world.entity.MemberWorld;
import sweetgirl.croissant.world.entity.WorldCharacter;
import sweetgirl.croissant.world.repository.MemberPlanetArrivalRepository;
import sweetgirl.croissant.world.repository.MemberWorldRepository;
import sweetgirl.croissant.world.repository.WorldCharacterAssetRepository;
import sweetgirl.croissant.world.repository.WorldCharacterRepository;
import sweetgirl.croissant.world.service.WorldCatalogService;
import sweetgirl.croissant.world.service.WorldProgress;
import sweetgirl.croissant.world.service.WorldProgressCalculator;

@ExtendWith(MockitoExtension.class)
class MonthlyReportAppearanceReaderTest {
	private static final Long MEMBER_ID = 7L;

	@Mock
	private MemberReader members;
	@Mock
	private MemberWorldRepository worlds;
	@Mock
	private WorldCharacterRepository characters;
	@Mock
	private WorldCharacterAssetRepository characterAssets;
	@Mock
	private MemberPlanetArrivalRepository arrivals;
	@Mock
	private BreadRecordRepository records;
	@Mock
	private WorldCatalogService catalogService;
	@Mock
	private WorldProgressCalculator calculator;

	@InjectMocks
	private MonthlyReportAppearanceReader reader;

	@BeforeEach
	void setUp() {
		// 마스코트(dog)로 시작해 지금은 햄찌를 데리고 다닌다 — 리포트는 지금 데리고 다니는 친구로 만든다
		MemberWorld world = MemberWorld.start(MEMBER_ID, "dog");
		world.selectCharacter("hamster");
		given(worlds.findById(MEMBER_ID)).willReturn(Optional.of(world));
		given(characters.findById("hamster"))
			.willReturn(Optional.of(WorldCharacter.create("hamster", "햄찌", null, "earth", 1, false)));
		WorldPlanetResponse earth = new WorldPlanetResponse("earth", "지구", null, 1, 10, null, true, 0, 0, false,
			null, List.of());
		given(catalogService.getCatalog()).willReturn(new WorldCatalogResponse(1, List.of(earth), List.of()));
		given(calculator.calculate(any(), anyInt(), any())).willReturn(new WorldProgress("earth", null, null, 0, 0));
		given(characterAssets.findById(any())).willReturn(Optional.empty());
		Member member = mock(Member.class);
		given(member.getNickname()).willReturn("빵순이");
		given(members.getMember(MEMBER_ID)).willReturn(member);
	}

	@Test
	@DisplayName("지금 데리고 다니는 친구를 카탈로그 이름으로 싣는다")
	void usesCatalogName() {
		MonthlyReportResponse.Character character = reader.createAppearanceDraft(MEMBER_ID, "2026-09").character();
		assertThat(character.code()).isEqualTo("hamster");
		assertThat(character.name()).isEqualTo("햄찌");
	}
}

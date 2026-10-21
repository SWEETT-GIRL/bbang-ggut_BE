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
import sweetgirl.croissant.world.dto.response.WorldCatalogResponse;
import sweetgirl.croissant.world.dto.response.WorldPlanetResponse;
import sweetgirl.croissant.world.entity.MemberCharacter;
import sweetgirl.croissant.world.entity.MemberCharacterId;
import sweetgirl.croissant.world.entity.MemberWorld;
import sweetgirl.croissant.world.entity.WorldCharacter;
import sweetgirl.croissant.world.repository.MemberCharacterRepository;
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
	private MemberCharacterRepository memberCharacters;
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
		MemberWorld world = MemberWorld.start(MEMBER_ID, "hamster");
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
	@DisplayName("친구에게 지어 준 이름이 있으면 리포트에 그 이름으로 싣는다")
	void usesNickname() {
		MemberCharacter friend = mock(MemberCharacter.class);
		given(friend.getNickname()).willReturn("콩");
		given(memberCharacters.findById(new MemberCharacterId(MEMBER_ID, "hamster"))).willReturn(Optional.of(friend));

		assertThat(reader.createAppearanceDraft(MEMBER_ID, "2026-09").character().name()).isEqualTo("콩");
	}

	@Test
	@DisplayName("지어 준 이름이 없으면 카탈로그 이름으로 싣는다")
	void fallsBackToCatalogName() {
		MemberCharacter friend = mock(MemberCharacter.class);
		given(memberCharacters.findById(new MemberCharacterId(MEMBER_ID, "hamster"))).willReturn(Optional.of(friend));

		assertThat(reader.createAppearanceDraft(MEMBER_ID, "2026-09").character().name()).isEqualTo("햄찌");
	}
}

package sweetgirl.croissant.world.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.reward.service.RankingRewardService;
import sweetgirl.croissant.world.dto.response.MemberCharacterResponse;
import sweetgirl.croissant.world.dto.response.MemberWorldResponse;
import sweetgirl.croissant.world.dto.response.WorldCatalogResponse;
import sweetgirl.croissant.world.dto.response.WorldCharacterResponse;
import sweetgirl.croissant.world.dto.response.WorldPlanetResponse;
import sweetgirl.croissant.world.dto.response.WorldStageResponse;
import sweetgirl.croissant.world.entity.MemberPlanetArrival;
import sweetgirl.croissant.world.entity.WorldFixtures;
import sweetgirl.croissant.world.enums.AcquiredBy;
import sweetgirl.croissant.world.enums.CharacterStatus;
import sweetgirl.croissant.world.enums.PlanetGroup;
import sweetgirl.croissant.world.exception.WorldException;
import sweetgirl.croissant.world.repository.MemberCharacterRepository;
import sweetgirl.croissant.world.repository.MemberPlanetArrivalRepository;
import sweetgirl.croissant.world.repository.MemberWakeTicketRepository;
import sweetgirl.croissant.world.repository.MemberWorldRepository;

@ExtendWith(MockitoExtension.class)
class WorldQueryServiceTest {

	private static final Long MEMBER_ID = 1L;

	@Mock
	private MemberWorldRepository memberWorldRepository;
	@Mock
	private MemberCharacterRepository memberCharacterRepository;
	@Mock
	private MemberPlanetArrivalRepository memberPlanetArrivalRepository;
	@Mock
	private MemberWakeTicketRepository memberWakeTicketRepository;
	@Mock
	private BreadRecordRepository breadRecordRepository;
	@Mock
	private WorldCatalogService worldCatalogService;

	@Mock
	private RankingRewardService rankingRewardService;

	@InjectMocks
	private WorldQueryService worldQueryService;

	/** 진행도 계산은 계산기 테스트가 붙잡고 있다. 여기서는 실체를 넣어 조립 결과를 본다. */
	@BeforeEach
	void injectCalculator() {
		ReflectionTestUtils.setField(
			worldQueryService, "worldProgressCalculator", new WorldProgressCalculator());
	}

	private static final WorldCatalogResponse CATALOG = new WorldCatalogResponse(
		7,
		List.of(
			planet("earth", 1, 10, null, 0, 2),
			planet("mars", 2, 30, "earth", 1, 3)),
		List.of(
			new WorldCharacterResponse("dog", "빵댕이", null, "earth", 1, true, Map.of()),
			new WorldCharacterResponse("chick", "삐약이", null, "mars", 1, false, Map.of())));

	private static WorldPlanetResponse planet(
			String code, int sortOrder, int span, String unlockAfter, int rouletteSize, int adsPerWake) {
		return new WorldPlanetResponse(
			code, code, PlanetGroup.SOLAR, sortOrder, span, unlockAfter, false,
			rouletteSize, adsPerWake, true, null,
			List.of(new WorldStageResponse("village", "마을", 1, 0, null),
				new WorldStageResponse("sky", "하늘", 2, 3, null)));
	}

	private void givenMember(int maxRecordCount, long recordCount, List<MemberPlanetArrival> arrivals) {
		given(memberWorldRepository.findById(MEMBER_ID))
			.willReturn(Optional.of(WorldFixtures.memberWorld(MEMBER_ID, "dog", maxRecordCount)));
		given(worldCatalogService.getCatalog()).willReturn(CATALOG);
		given(breadRecordRepository.countByMemberIdAndDeletedAtIsNull(MEMBER_ID))
			.willReturn(recordCount);
		given(memberPlanetArrivalRepository.findAllByIdMemberId(MEMBER_ID)).willReturn(arrivals);
	}

	@Test
	@DisplayName("기록 12 개면 화성에 서고, 룰렛을 안 돌린 화성이 대기로 잡힌다")
	void putsMemberOnMarsWithPendingRoulette() {
		givenMember(12, 12L, List.of(
			WorldFixtures.arrival(MEMBER_ID, "earth"),
			WorldFixtures.arrival(MEMBER_ID, "mars")));
		given(memberCharacterRepository.findAllByIdMemberId(MEMBER_ID)).willReturn(List.of());
		given(memberWakeTicketRepository.countByMemberIdAndUsedAtIsNull(MEMBER_ID)).willReturn(1);

		MemberWorldResponse response = worldQueryService.getMemberWorld(MEMBER_ID);

		assertThat(response.catalogVersion()).isEqualTo(7);
		assertThat(response.planet()).isEqualTo("mars");
		assertThat(response.stage()).isEqualTo("village");
		// 화성 마을은 화성에 닿은 기록 10 개에서 시작한다
		assertThat(response.levelStartAt()).isEqualTo(10);
		assertThat(response.recordCount()).isEqualTo(12L);
		assertThat(response.selectedCharacter()).isEqualTo("dog");
		assertThat(response.wakeTickets()).isEqualTo(1);
		// 지구는 룰렛 인원이 0 이라 돌릴 것이 없다
		assertThat(response.pendingRoulettePlanets()).containsExactly("mars");
	}

	@Test
	@DisplayName("밀린 룰렛은 코드 글자순이 아니라 여정 순서다 — 화성이 목성보다 먼저")
	void ordersPendingRouletteByJourney() {
		given(worldCatalogService.getCatalog()).willReturn(new WorldCatalogResponse(
			7,
			List.of(
				planet("earth", 1, 10, null, 0, 2),
				planet("mars", 2, 30, "earth", 1, 3),
				planet("jupiter", 3, 30, "mars", 1, 3)),
			List.of()));
		given(memberPlanetArrivalRepository.findAllByIdMemberId(MEMBER_ID)).willReturn(List.of(
			WorldFixtures.arrival(MEMBER_ID, "jupiter"),
			WorldFixtures.arrival(MEMBER_ID, "mars")));

		assertThat(worldQueryService.pendingRoulettePlanets(MEMBER_ID)).containsExactly("mars", "jupiter");
	}

	@Test
	@DisplayName("보여주는 수는 살아 있는 기록, 진행도는 최고 높이로 나뉜다")
	void separatesDisplayedCountFromProgressHeight() {
		givenMember(40, 12L, List.of());
		given(memberCharacterRepository.findAllByIdMemberId(MEMBER_ID)).willReturn(List.of());
		given(memberWakeTicketRepository.countByMemberIdAndUsedAtIsNull(MEMBER_ID)).willReturn(0);

		MemberWorldResponse response = worldQueryService.getMemberWorld(MEMBER_ID);

		assertThat(response.recordCount()).isEqualTo(12L);
		assertThat(response.maxRecordCount()).isEqualTo(40);
		assertThat(response.planet()).isEqualTo("mars");
	}

	@Test
	@DisplayName("광고 편수는 상수가 아니라 그 친구가 사는 행성 값이다")
	void readsAdsRequiredFromTheCharactersPlanet() {
		givenMember(12, 12L, List.of());
		given(memberCharacterRepository.findAllByIdMemberId(MEMBER_ID)).willReturn(List.of(
			WorldFixtures.memberCharacter(MEMBER_ID, "dog", CharacterStatus.AWAKE, 0, AcquiredBy.FIRST),
			WorldFixtures.memberCharacter(MEMBER_ID, "chick", CharacterStatus.SLEEPING, 1, null)));
		given(memberWakeTicketRepository.countByMemberIdAndUsedAtIsNull(MEMBER_ID)).willReturn(0);

		MemberWorldResponse response = worldQueryService.getMemberWorld(MEMBER_ID);

		assertThat(response.characters())
			.extracting(MemberCharacterResponse::code, MemberCharacterResponse::adsRequired)
			.containsExactly(
				org.assertj.core.groups.Tuple.tuple("chick", 3),
				org.assertj.core.groups.Tuple.tuple("dog", 2));
	}

	@Test
	@DisplayName("첫 친구 연출을 봤는지 싣는다 — 첫 조회가 마스코트를 주므로 characters 로는 못 가린다")
	void carriesWelcomedAt() {
		givenMember(0, 0L, List.of());
		given(memberCharacterRepository.findAllByIdMemberId(MEMBER_ID)).willReturn(List.of(
			WorldFixtures.memberCharacter(MEMBER_ID, "dog", CharacterStatus.AWAKE, 0, AcquiredBy.FIRST)));
		given(memberWakeTicketRepository.countByMemberIdAndUsedAtIsNull(MEMBER_ID)).willReturn(0);

		assertThat(worldQueryService.getMemberWorld(MEMBER_ID).welcomedAt()).isNull();

		LocalDateTime seenAt = LocalDateTime.of(2026, 9, 23, 10, 0);
		memberWorldRepository.findById(MEMBER_ID).orElseThrow().welcome(seenAt);

		assertThat(worldQueryService.getMemberWorld(MEMBER_ID).welcomedAt()).isEqualTo(seenAt);
	}

	@Test
	@DisplayName("여정을 시작하지 않은 회원이면 예외다")
	void throwsWhenJourneyHasNotStarted() {
		given(memberWorldRepository.findById(MEMBER_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> worldQueryService.getMemberWorld(MEMBER_ID))
			.isInstanceOf(WorldException.class)
			.hasMessageContaining("여정이 아직 시작되지 않았습니다");
	}
}

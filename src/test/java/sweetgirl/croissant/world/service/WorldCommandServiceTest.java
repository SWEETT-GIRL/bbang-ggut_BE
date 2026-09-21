package sweetgirl.croissant.world.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.world.dto.response.WorldCatalogResponse;
import sweetgirl.croissant.world.dto.response.WorldCharacterResponse;
import sweetgirl.croissant.world.dto.response.WorldPlanetResponse;
import sweetgirl.croissant.world.dto.response.WorldRouletteResponse;
import sweetgirl.croissant.world.dto.response.WorldWakeResponse;
import sweetgirl.croissant.world.entity.MemberCharacterId;
import sweetgirl.croissant.world.entity.MemberWorld;
import sweetgirl.croissant.world.entity.WorldFixtures;
import sweetgirl.croissant.world.enums.AcquiredBy;
import sweetgirl.croissant.world.enums.CharacterStatus;
import sweetgirl.croissant.world.enums.PlanetGroup;
import sweetgirl.croissant.world.enums.WakeTicketSource;
import sweetgirl.croissant.world.exception.WorldException;
import sweetgirl.croissant.world.repository.MemberCharacterRepository;
import sweetgirl.croissant.world.repository.MemberPlanetArrivalRepository;
import sweetgirl.croissant.world.repository.MemberWakeTicketRepository;
import sweetgirl.croissant.world.repository.MemberWorldRepository;
import sweetgirl.croissant.world.repository.WorldCharacterRepository;

@ExtendWith(MockitoExtension.class)
class WorldCommandServiceTest {

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
	private WorldCharacterRepository worldCharacterRepository;
	@Mock
	private BreadRecordRepository breadRecordRepository;
	@Mock
	private WorldCatalogService worldCatalogService;
	@Mock
	private WorldProgressCalculator worldProgressCalculator;

	@InjectMocks
	private WorldCommandService worldCommandService;

	/** 여정을 이미 시작한 회원. 도착 이력 단계까지 흐르도록 최소한만 세운다. */
	private void givenStartedMember(int maxRecordCount, long recordCount) {
		given(memberWorldRepository.existsById(MEMBER_ID)).willReturn(true);
		given(memberWorldRepository.findById(MEMBER_ID))
			.willReturn(Optional.of(WorldFixtures.memberWorld(MEMBER_ID, "dog", maxRecordCount)));
		given(breadRecordRepository.countByMemberIdAndDeletedAtIsNull(MEMBER_ID))
			.willReturn(recordCount);
		given(worldCatalogService.getCatalog())
			.willReturn(new WorldCatalogResponse(1, List.of(), List.of()));
	}

	@Nested
	@DisplayName("여정 시작")
	class Start {

		@Test
		@DisplayName("처음 들어오면 마스코트를 깨어난 채로 지급한다")
		void grantsMascotOnFirstVisit() {
			given(memberWorldRepository.existsById(MEMBER_ID)).willReturn(false);
			given(worldCharacterRepository.findByMascotTrue())
				.willReturn(Optional.of(WorldFixtures.character("dog", "빵댕이", "earth", 1, true)));
			given(memberWorldRepository.findById(MEMBER_ID))
				.willReturn(Optional.of(WorldFixtures.memberWorld(MEMBER_ID, "dog", 0)));
			given(breadRecordRepository.countByMemberIdAndDeletedAtIsNull(MEMBER_ID)).willReturn(0L);
			given(worldCatalogService.getCatalog())
				.willReturn(new WorldCatalogResponse(1, List.of(), List.of()));
			given(memberPlanetArrivalRepository.findAllByIdMemberId(MEMBER_ID)).willReturn(List.of());
			given(worldProgressCalculator.reachedPlanetCodes(List.of(), 0)).willReturn(List.of());

			worldCommandService.syncProgress(MEMBER_ID);

			then(memberWorldRepository).should().startIfAbsent(MEMBER_ID, "dog");
			then(memberCharacterRepository).should().grantIfAbsent(MEMBER_ID, "dog", "FIRST");
		}

		@Test
		@DisplayName("이미 시작했으면 다시 만들지 않는다 — 홈 진입마다 쓰지 않기 위해서다")
		void doesNotWriteWhenAlreadyStarted() {
			givenStartedMember(0, 0L);
			given(memberPlanetArrivalRepository.findAllByIdMemberId(MEMBER_ID)).willReturn(List.of());
			given(worldProgressCalculator.reachedPlanetCodes(List.of(), 0)).willReturn(List.of());

			worldCommandService.syncProgress(MEMBER_ID);

			then(memberWorldRepository).should(never()).startIfAbsent(any(), anyString());
			then(memberCharacterRepository).should(never()).grantIfAbsent(any(), anyString(), anyString());
			then(worldCharacterRepository).should(never()).findByMascotTrue();
		}

		@Test
		@DisplayName("마스코트가 지정돼 있지 않으면 예외다")
		void throwsWhenMascotIsMissing() {
			given(memberWorldRepository.existsById(MEMBER_ID)).willReturn(false);
			given(worldCharacterRepository.findByMascotTrue()).willReturn(Optional.empty());

			assertThatThrownBy(() -> worldCommandService.syncProgress(MEMBER_ID))
				.isInstanceOf(WorldException.class)
				.hasMessageContaining("첫 친구로 지급할 캐릭터가 지정되지 않았습니다");
		}

		@Test
		@DisplayName("시작한 뒤에도 행이 없으면 예외다")
		void throwsWhenMemberWorldIsMissing() {
			given(memberWorldRepository.existsById(MEMBER_ID)).willReturn(true);
			given(memberWorldRepository.findById(MEMBER_ID)).willReturn(Optional.empty());

			assertThatThrownBy(() -> worldCommandService.syncProgress(MEMBER_ID))
				.isInstanceOf(WorldException.class);
		}
	}

	@Nested
	@DisplayName("친구 고르기")
	class SelectCharacter {

		@Test
		@DisplayName("이미 만난 친구면 바꿔 준다")
		void changesSelectedCharacter() {
			MemberWorld memberWorld = WorldFixtures.memberWorld(MEMBER_ID, "dog", 0);
			given(memberWorldRepository.findById(MEMBER_ID)).willReturn(Optional.of(memberWorld));
			given(memberCharacterRepository.findById(new MemberCharacterId(MEMBER_ID, "cat")))
				.willReturn(Optional.of(WorldFixtures.memberCharacter(
					MEMBER_ID, "cat", CharacterStatus.AWAKE, 0, AcquiredBy.ROULETTE)));

			worldCommandService.selectCharacter(MEMBER_ID, "cat");

			assertThat(memberWorld.getSelectedCharacter()).isEqualTo("cat");
		}

		@Test
		@DisplayName("아직 자고 있는 친구는 고를 수 없다")
		void rejectsSleepingCharacter() {
			given(memberWorldRepository.findById(MEMBER_ID))
				.willReturn(Optional.of(WorldFixtures.memberWorld(MEMBER_ID, "dog", 0)));
			given(memberCharacterRepository.findById(new MemberCharacterId(MEMBER_ID, "cat")))
				.willReturn(Optional.of(WorldFixtures.memberCharacter(
					MEMBER_ID, "cat", CharacterStatus.SLEEPING, 1, null)));

			assertThatThrownBy(() -> worldCommandService.selectCharacter(MEMBER_ID, "cat"))
				.isInstanceOf(WorldException.class)
				.hasMessageContaining("아직 만나지 못한 친구");
		}

		@Test
		@DisplayName("만난 적 없는 친구면 예외다")
		void rejectsUnknownCharacter() {
			given(memberWorldRepository.findById(MEMBER_ID))
				.willReturn(Optional.of(WorldFixtures.memberWorld(MEMBER_ID, "dog", 0)));
			given(memberCharacterRepository.findById(new MemberCharacterId(MEMBER_ID, "quoka")))
				.willReturn(Optional.empty());

			assertThatThrownBy(() -> worldCommandService.selectCharacter(MEMBER_ID, "quoka"))
				.isInstanceOf(WorldException.class)
				.hasMessageContaining("그런 친구가 없습니다");
		}
	}

	@Nested
	@DisplayName("첫 친구 연출 확인")
	class Welcome {

		@Test
		@DisplayName("처음 본 때를 남긴다")
		void marksWelcomed() {
			MemberWorld memberWorld = WorldFixtures.memberWorld(MEMBER_ID, "dog", 0);
			given(memberWorldRepository.findById(MEMBER_ID)).willReturn(Optional.of(memberWorld));

			worldCommandService.welcome(MEMBER_ID);

			assertThat(memberWorld.getWelcomedAt()).isNotNull();
		}

		@Test
		@DisplayName("두 번 불러도 처음 본 때가 덮이지 않는다")
		void keepsTheFirstWelcomedAt() {
			MemberWorld memberWorld = WorldFixtures.memberWorld(MEMBER_ID, "dog", 0);
			given(memberWorldRepository.findById(MEMBER_ID)).willReturn(Optional.of(memberWorld));

			worldCommandService.welcome(MEMBER_ID);
			var firstSeen = memberWorld.getWelcomedAt();
			worldCommandService.welcome(MEMBER_ID);

			assertThat(memberWorld.getWelcomedAt()).isEqualTo(firstSeen);
		}
	}

	/** 지구는 룰렛이 없고(마스코트로 줌) 화성은 한 명을 준다. */
	private static final WorldCatalogResponse CATALOG = new WorldCatalogResponse(
		1,
		List.of(
			planet("earth", 1, 0),
			planet("mars", 2, 1),
			planet("jupiter", 3, 1, 3, false)),
		List.of(
			new WorldCharacterResponse("dog", "빵댕이", "earth", 1, true, Map.of()),
			new WorldCharacterResponse("chick", "삐약이", "mars", 1, false, Map.of()),
			new WorldCharacterResponse("frog", "개굴이", "mars", 2, false, Map.of()),
			new WorldCharacterResponse("ber", "곰도리", "jupiter", 1, false, Map.of())));

	private static WorldPlanetResponse planet(String code, int sortOrder, int rouletteSize) {
		return planet(code, sortOrder, rouletteSize, 2, true);
	}

	private static WorldPlanetResponse planet(
			String code, int sortOrder, int rouletteSize, int adsPerWake, boolean referralOk) {
		return new WorldPlanetResponse(
			code, code, PlanetGroup.SOLAR, sortOrder, 10, null, false,
			rouletteSize, adsPerWake, referralOk, null, List.of());
	}

	@Nested
	@DisplayName("행성 도착 룰렛")
	class Roulette {

		private void givenArrivedAtMars() {
			given(worldCatalogService.getCatalog()).willReturn(CATALOG);
			given(memberPlanetArrivalRepository.findAllByIdMemberId(MEMBER_ID))
				.willReturn(List.of(WorldFixtures.arrival(MEMBER_ID, "mars")));
		}

		@Test
		@DisplayName("아직 못 만난 친구 중에서 뽑아 깨어난 채로 준다")
		void grantsOneOfTheSleepingFriends() {
			givenArrivedAtMars();
			given(memberCharacterRepository.findAllByIdMemberId(MEMBER_ID)).willReturn(List.of(
				WorldFixtures.memberCharacter(
					MEMBER_ID, "chick", CharacterStatus.AWAKE, 0, AcquiredBy.ROULETTE)));
			given(memberPlanetArrivalRepository.markRouletteSpun(MEMBER_ID, "mars")).willReturn(1);

			WorldRouletteResponse response = worldCommandService.spinRoulette(MEMBER_ID, "mars");

			// 이미 가진 chick 은 후보가 아니므로 frog 만 남는다
			assertThat(response.characterCodes()).containsExactly("frog");
			then(memberCharacterRepository).should().grantIfAbsent(MEMBER_ID, "frog", "ROULETTE");
		}

		@Test
		@DisplayName("두 번째 호출은 이미 돌렸다고 막는다")
		void rejectsSecondSpin() {
			givenArrivedAtMars();
			given(memberCharacterRepository.findAllByIdMemberId(MEMBER_ID)).willReturn(List.of());
			given(memberPlanetArrivalRepository.markRouletteSpun(MEMBER_ID, "mars")).willReturn(0);

			assertThatThrownBy(() -> worldCommandService.spinRoulette(MEMBER_ID, "mars"))
				.isInstanceOf(WorldException.class)
				.hasMessageContaining("이미 룰렛을 돌린");
			then(memberCharacterRepository).should(never())
				.grantIfAbsent(any(), anyString(), anyString());
		}

		@Test
		@DisplayName("닿지 않은 행성이면 막는다")
		void rejectsUnreachedPlanet() {
			given(worldCatalogService.getCatalog()).willReturn(CATALOG);
			given(memberPlanetArrivalRepository.findAllByIdMemberId(MEMBER_ID)).willReturn(List.of());

			assertThatThrownBy(() -> worldCommandService.spinRoulette(MEMBER_ID, "mars"))
				.isInstanceOf(WorldException.class)
				.hasMessageContaining("아직 닿지 않은 행성");
		}

		@Test
		@DisplayName("줄 친구가 없으면 룰렛을 태우지 않는다")
		void doesNotBurnTheRouletteWhenNobodyIsLeft() {
			givenArrivedAtMars();
			given(memberCharacterRepository.findAllByIdMemberId(MEMBER_ID)).willReturn(List.of(
				WorldFixtures.memberCharacter(
					MEMBER_ID, "chick", CharacterStatus.AWAKE, 0, AcquiredBy.ROULETTE),
				WorldFixtures.memberCharacter(
					MEMBER_ID, "frog", CharacterStatus.AWAKE, 0, AcquiredBy.AD)));

			assertThatThrownBy(() -> worldCommandService.spinRoulette(MEMBER_ID, "mars"))
				.isInstanceOf(WorldException.class)
				.hasMessageContaining("만날 친구가 남아 있지 않");
			// 돌린 것으로 표시했다면 다시는 못 돌린다 — 표시 전에 막아야 한다
			then(memberPlanetArrivalRepository).should(never()).markRouletteSpun(any(), anyString());
		}

		@Test
		@DisplayName("룰렛이 없는 행성이면 막는다 — 지구는 첫 친구를 마스코트로 준다")
		void rejectsPlanetWithoutRoulette() {
			given(worldCatalogService.getCatalog()).willReturn(CATALOG);

			assertThatThrownBy(() -> worldCommandService.spinRoulette(MEMBER_ID, "earth"))
				.isInstanceOf(WorldException.class)
				.hasMessageContaining("돌릴 룰렛이 없");
		}

		@Test
		@DisplayName("카탈로그에 없는 행성이면 예외다")
		void rejectsUnknownPlanet() {
			given(worldCatalogService.getCatalog()).willReturn(CATALOG);

			assertThatThrownBy(() -> worldCommandService.spinRoulette(MEMBER_ID, "pluto"))
				.isInstanceOf(WorldException.class)
				.hasMessageContaining("그런 행성이 없습니다");
		}
	}

	@Nested
	@DisplayName("광고로 깨우기")
	class AdViews {

		/** 화성에 닿고 룰렛까지 돌린 상태. */
		private void givenSpunAtMars() {
			given(worldCatalogService.getCatalog()).willReturn(CATALOG);
			given(memberPlanetArrivalRepository.findAllByIdMemberId(MEMBER_ID))
				.willReturn(List.of(WorldFixtures.arrival(MEMBER_ID, "mars", true)));
		}

		@Test
		@DisplayName("한 편으로는 아직 안 깨어난다")
		void countsWithoutAwakening() {
			givenSpunAtMars();
			given(memberCharacterRepository.addAdView(MEMBER_ID, "frog")).willReturn(1);
			given(memberCharacterRepository.findById(new MemberCharacterId(MEMBER_ID, "frog")))
				.willReturn(Optional.of(WorldFixtures.memberCharacter(
					MEMBER_ID, "frog", CharacterStatus.SLEEPING, 1, null)));

			WorldWakeResponse response = worldCommandService.addAdView(MEMBER_ID, "frog");

			assertThat(response.adViews()).isEqualTo(1);
			assertThat(response.adsRequired()).isEqualTo(2);
			assertThat(response.awakened()).isFalse();
			then(memberCharacterRepository).should(never())
				.awakeIfSleeping(any(), anyString(), anyString());
		}

		@Test
		@DisplayName("필요한 편수를 채우면 그 자리에서 깨어난다")
		void awakensWhenEnough() {
			givenSpunAtMars();
			given(memberCharacterRepository.addAdView(MEMBER_ID, "frog")).willReturn(1);
			given(memberCharacterRepository.findById(new MemberCharacterId(MEMBER_ID, "frog")))
				.willReturn(Optional.of(WorldFixtures.memberCharacter(
					MEMBER_ID, "frog", CharacterStatus.SLEEPING, 2, null)));
			given(memberCharacterRepository.awakeIfSleeping(MEMBER_ID, "frog", "AD")).willReturn(1);

			WorldWakeResponse response = worldCommandService.addAdView(MEMBER_ID, "frog");

			assertThat(response.awakened()).isTrue();
		}

		@Test
		@DisplayName("필요한 편수는 그 친구가 사는 행성 값이다 — 목성은 세 편")
		void readsAdsRequiredFromThePlanet() {
			given(worldCatalogService.getCatalog()).willReturn(CATALOG);
			given(memberPlanetArrivalRepository.findAllByIdMemberId(MEMBER_ID))
				.willReturn(List.of(WorldFixtures.arrival(MEMBER_ID, "jupiter", true)));
			given(memberCharacterRepository.addAdView(MEMBER_ID, "ber")).willReturn(1);
			given(memberCharacterRepository.findById(new MemberCharacterId(MEMBER_ID, "ber")))
				.willReturn(Optional.of(WorldFixtures.memberCharacter(
					MEMBER_ID, "ber", CharacterStatus.SLEEPING, 2, null)));

			WorldWakeResponse response = worldCommandService.addAdView(MEMBER_ID, "ber");

			// 화성 기준(2편)이면 깨어났겠지만 목성은 세 편이다
			assertThat(response.adsRequired()).isEqualTo(3);
			assertThat(response.awakened()).isFalse();
		}

		@Test
		@DisplayName("이미 깨어난 친구면 막는다")
		void rejectsAwakeCharacter() {
			givenSpunAtMars();
			given(memberCharacterRepository.addAdView(MEMBER_ID, "frog")).willReturn(0);

			assertThatThrownBy(() -> worldCommandService.addAdView(MEMBER_ID, "frog"))
				.isInstanceOf(WorldException.class)
				.hasMessageContaining("이미 깨어난 친구");
		}

		@Test
		@DisplayName("룰렛을 안 돌렸으면 광고부터 볼 수 없다")
		void requiresRouletteFirst() {
			given(worldCatalogService.getCatalog()).willReturn(CATALOG);
			given(memberPlanetArrivalRepository.findAllByIdMemberId(MEMBER_ID))
				.willReturn(List.of(WorldFixtures.arrival(MEMBER_ID, "mars", false)));

			assertThatThrownBy(() -> worldCommandService.addAdView(MEMBER_ID, "frog"))
				.isInstanceOf(WorldException.class)
				.hasMessageContaining("먼저 이 행성의 룰렛");
			then(memberCharacterRepository).should(never()).addAdView(any(), anyString());
		}

		@Test
		@DisplayName("룰렛이 없는 행성(지구)은 룰렛 조건을 건너뛴다")
		void skipsRouletteCheckWhenPlanetHasNone() {
			given(worldCatalogService.getCatalog()).willReturn(CATALOG);
			given(memberPlanetArrivalRepository.findAllByIdMemberId(MEMBER_ID))
				.willReturn(List.of(WorldFixtures.arrival(MEMBER_ID, "earth", false)));
			given(memberCharacterRepository.addAdView(MEMBER_ID, "dog")).willReturn(1);
			given(memberCharacterRepository.findById(new MemberCharacterId(MEMBER_ID, "dog")))
				.willReturn(Optional.of(WorldFixtures.memberCharacter(
					MEMBER_ID, "dog", CharacterStatus.SLEEPING, 1, null)));

			assertThat(worldCommandService.addAdView(MEMBER_ID, "dog").adViews()).isEqualTo(1);
		}

		@Test
		@DisplayName("닿지 않은 행성의 친구는 깨울 수 없다")
		void rejectsUnreachedPlanet() {
			given(worldCatalogService.getCatalog()).willReturn(CATALOG);
			given(memberPlanetArrivalRepository.findAllByIdMemberId(MEMBER_ID)).willReturn(List.of());

			assertThatThrownBy(() -> worldCommandService.addAdView(MEMBER_ID, "frog"))
				.isInstanceOf(WorldException.class)
				.hasMessageContaining("아직 닿지 않은 행성");
		}

		@Test
		@DisplayName("카탈로그에 없는 친구면 예외다")
		void rejectsUnknownCharacter() {
			given(worldCatalogService.getCatalog()).willReturn(CATALOG);

			assertThatThrownBy(() -> worldCommandService.addAdView(MEMBER_ID, "quoka"))
				.isInstanceOf(WorldException.class)
				.hasMessageContaining("그런 친구가 없습니다");
		}
	}

	@Nested
	@DisplayName("티켓으로 깨우기")
	class WakeTicket {

		private void givenSpunAtMars() {
			given(worldCatalogService.getCatalog()).willReturn(CATALOG);
			given(memberPlanetArrivalRepository.findAllByIdMemberId(MEMBER_ID))
				.willReturn(List.of(WorldFixtures.arrival(MEMBER_ID, "mars", true)));
		}

		@Test
		@DisplayName("티켓 한 장으로 광고 없이 깨운다")
		void awakensWithoutAds() {
			givenSpunAtMars();
			given(memberWakeTicketRepository.findFirstByMemberIdAndUsedAtIsNullOrderByIdAsc(MEMBER_ID))
				.willReturn(Optional.of(
					WorldFixtures.wakeTicket(10L, MEMBER_ID, WakeTicketSource.REFERRAL)));
			given(memberWakeTicketRepository.useTicket(10L)).willReturn(1);

			WorldWakeResponse response = worldCommandService.useWakeTicket(MEMBER_ID, "frog");

			assertThat(response.awakened()).isTrue();
			then(memberCharacterRepository).should().grantIfAbsent(MEMBER_ID, "frog", "REFERRAL");
			then(memberCharacterRepository).should().awakeIfSleeping(MEMBER_ID, "frog", "REFERRAL");
		}

		@Test
		@DisplayName("쓸 티켓이 없으면 막는다")
		void rejectsWithoutTicket() {
			givenSpunAtMars();
			given(memberWakeTicketRepository.findFirstByMemberIdAndUsedAtIsNullOrderByIdAsc(MEMBER_ID))
				.willReturn(Optional.empty());

			assertThatThrownBy(() -> worldCommandService.useWakeTicket(MEMBER_ID, "frog"))
				.isInstanceOf(WorldException.class)
				.hasMessageContaining("깨우기 티켓이 없습니다");
		}

		@Test
		@DisplayName("같은 티켓을 남이 먼저 썼으면 막는다")
		void rejectsAlreadyUsedTicket() {
			givenSpunAtMars();
			given(memberWakeTicketRepository.findFirstByMemberIdAndUsedAtIsNullOrderByIdAsc(MEMBER_ID))
				.willReturn(Optional.of(
					WorldFixtures.wakeTicket(10L, MEMBER_ID, WakeTicketSource.REFERRAL)));
			given(memberWakeTicketRepository.useTicket(10L)).willReturn(0);

			assertThatThrownBy(() -> worldCommandService.useWakeTicket(MEMBER_ID, "frog"))
				.isInstanceOf(WorldException.class)
				.hasMessageContaining("깨우기 티켓이 없습니다");
			then(memberCharacterRepository).should(never())
				.grantIfAbsent(any(), anyString(), anyString());
		}

		@Test
		@DisplayName("추천 지름길을 막아둔 행성이면 티켓을 쓸 수 없다")
		void rejectsWhenReferralIsClosed() {
			given(worldCatalogService.getCatalog()).willReturn(CATALOG);
			given(memberPlanetArrivalRepository.findAllByIdMemberId(MEMBER_ID))
				.willReturn(List.of(WorldFixtures.arrival(MEMBER_ID, "jupiter", true)));

			assertThatThrownBy(() -> worldCommandService.useWakeTicket(MEMBER_ID, "ber"))
				.isInstanceOf(WorldException.class)
				.hasMessageContaining("친구 추천으로 깨울 수 없습니다");
			then(memberWakeTicketRepository).should(never())
				.findFirstByMemberIdAndUsedAtIsNullOrderByIdAsc(any());
		}
	}

	@Nested
	@DisplayName("도착 이력")
	class Arrivals {

		@Test
		@DisplayName("닿은 행성 중 이력이 없는 것만 남긴다")
		void recordsOnlyMissingArrivals() {
			givenStartedMember(0, 12L);
			given(memberPlanetArrivalRepository.findAllByIdMemberId(MEMBER_ID))
				.willReturn(List.of(WorldFixtures.arrival(MEMBER_ID, "earth")));
			given(worldProgressCalculator.reachedPlanetCodes(List.of(), 12))
				.willReturn(List.of("earth", "mars"));

			worldCommandService.syncProgress(MEMBER_ID);

			then(memberPlanetArrivalRepository).should(never()).arriveIfAbsent(MEMBER_ID, "earth");
			then(memberPlanetArrivalRepository).should(times(1)).arriveIfAbsent(MEMBER_ID, "mars");
		}

		@Test
		@DisplayName("저장된 최고 높이가 0 이어도 지금 기록 수로 높이를 잡는다")
		void usesLiveRecordCountWhenStoredHeightLagsBehind() {
			givenStartedMember(0, 50L);
			given(memberPlanetArrivalRepository.findAllByIdMemberId(MEMBER_ID)).willReturn(List.of());
			given(worldProgressCalculator.reachedPlanetCodes(List.of(), 50)).willReturn(List.of());

			worldCommandService.syncProgress(MEMBER_ID);

			then(worldProgressCalculator).should().reachedPlanetCodes(List.of(), 50);
		}

		@Test
		@DisplayName("기록을 지워 지금 수가 줄어도 저장된 최고 높이를 쓴다")
		void keepsStoredHeightWhenRecordsAreDeleted() {
			givenStartedMember(40, 12L);
			given(memberPlanetArrivalRepository.findAllByIdMemberId(MEMBER_ID)).willReturn(List.of());
			given(worldProgressCalculator.reachedPlanetCodes(List.of(), 40)).willReturn(List.of());

			worldCommandService.syncProgress(MEMBER_ID);

			then(worldProgressCalculator).should().reachedPlanetCodes(List.of(), 40);
		}
	}
}

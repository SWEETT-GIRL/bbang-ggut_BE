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
import sweetgirl.croissant.world.entity.MemberCharacterId;
import sweetgirl.croissant.world.entity.MemberWorld;
import sweetgirl.croissant.world.entity.WorldFixtures;
import sweetgirl.croissant.world.enums.AcquiredBy;
import sweetgirl.croissant.world.enums.CharacterStatus;
import sweetgirl.croissant.world.exception.WorldException;
import sweetgirl.croissant.world.repository.MemberCharacterRepository;
import sweetgirl.croissant.world.repository.MemberPlanetArrivalRepository;
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

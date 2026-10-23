package sweetgirl.croissant.reward.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.reward.code.RewardErrorCode;
import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.exception.RewardException;
import sweetgirl.croissant.world.code.WorldErrorCode;
import sweetgirl.croissant.world.dto.response.WorldPlanetResponse;
import sweetgirl.croissant.world.dto.response.WorldWakeResponse;
import sweetgirl.croissant.world.entity.MemberCharacter;
import sweetgirl.croissant.world.entity.MemberCharacterId;
import sweetgirl.croissant.world.exception.WorldException;
import sweetgirl.croissant.world.repository.MemberCharacterRepository;
import sweetgirl.croissant.world.service.WorldCommandService;

@ExtendWith(MockitoExtension.class)
class FriendCommandServiceTest {

	private static final Long MEMBER_ID = 7L;

	@Mock
	private WorldCommandService worldCommandService;
	@Mock
	private MemberCharacterRepository memberCharacterRepository;
	@Mock
	private CrumbGrantService crumbGrantService;
	@Mock
	private RewardSettings rewardSettings;
	@Mock
	private MemberReader memberReader;

	@InjectMocks
	private FriendCommandService friendCommandService;

	@BeforeEach
	void rules() {
		lenient().when(rewardSettings.get(RewardSettingKey.WAKE_FRIEND)).thenReturn(600);
	}

	private void givenWakeable() {
		WorldPlanetResponse planet = mock(WorldPlanetResponse.class);
		lenient().when(planet.adsPerWake()).thenReturn(2);
		given(worldCommandService.requireWakeable(MEMBER_ID, "frog")).willReturn(planet);
	}

	private void givenOwned(boolean awake, int adViews) {
		MemberCharacter owned = mock(MemberCharacter.class);
		lenient().when(owned.isAwake()).thenReturn(awake);
		lenient().when(owned.getAdViews()).thenReturn(adViews);
		given(memberCharacterRepository.findById(new MemberCharacterId(MEMBER_ID, "frog")))
			.willReturn(Optional.of(owned));
	}

	@Nested
	@DisplayName("부스러기로 깨우기")
	class WakeWithCrumbs {

		@Test
		@DisplayName("광고를 안 본 잠든 친구를 600 으로 깨운다")
		void wakes() {
			givenWakeable();
			given(memberCharacterRepository.findById(new MemberCharacterId(MEMBER_ID, "frog")))
				.willReturn(Optional.empty());
			given(memberCharacterRepository.grantIfAbsent(MEMBER_ID, "frog", "CRUMBS")).willReturn(1);
			given(crumbGrantService.spend(7L, CrumbReason.WAKE_FRIEND, 600, "wake:frog"))
				.willReturn(new SpendResult(true, 600, 700, 100));

			WorldWakeResponse response = friendCommandService.wakeWithCrumbs(MEMBER_ID, "frog");

			assertThat(response.awakened()).isTrue();
			assertThat(response.adsRequired()).isEqualTo(2);
			assertThat(response.rewards()).singleElement().satisfies(reward -> {
				assertThat(reward.amount()).isEqualTo(-600);
				assertThat(reward.to()).isEqualTo(100);
				assertThat(reward.reason()).isEqualTo(CrumbReason.WAKE_FRIEND);
			});
		}

		@Test
		@DisplayName("부스러기가 모자라면 예외가 올라가 깨운 것까지 되돌려진다")
		void insufficient() {
			givenWakeable();
			given(memberCharacterRepository.findById(new MemberCharacterId(MEMBER_ID, "frog")))
				.willReturn(Optional.empty());
			given(memberCharacterRepository.grantIfAbsent(MEMBER_ID, "frog", "CRUMBS")).willReturn(1);
			willThrow(new RewardException(RewardErrorCode.REWARD_CRUMBS_INSUFFICIENT))
				.given(crumbGrantService).spend(anyLong(), any(), anyInt(), anyString());

			assertThatThrownBy(() -> friendCommandService.wakeWithCrumbs(MEMBER_ID, "frog"))
				.isInstanceOf(RewardException.class)
				.extracting("errorCode").isEqualTo(RewardErrorCode.REWARD_CRUMBS_INSUFFICIENT);
		}

		@Test
		@DisplayName("이미 깨어 있는 친구면 부스러기를 빼지 않는다")
		void alreadyAwake() {
			givenWakeable();
			givenOwned(true, 0);

			assertThatThrownBy(() -> friendCommandService.wakeWithCrumbs(MEMBER_ID, "frog"))
				.isInstanceOf(WorldException.class)
				.extracting("errorCode").isEqualTo(WorldErrorCode.WORLD_CHARACTER_ALREADY_AWAKE);
			then(crumbGrantService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("광고를 한 편이라도 본 친구는 REWARD409_3 이고 부스러기를 빼지 않는다")
		void adStarted() {
			givenWakeable();
			givenOwned(false, 1);

			assertThatThrownBy(() -> friendCommandService.wakeWithCrumbs(MEMBER_ID, "frog"))
				.isInstanceOf(RewardException.class)
				.extracting("errorCode").isEqualTo(RewardErrorCode.REWARD_WAKE_AD_STARTED);
			then(crumbGrantService).shouldHaveNoInteractions();
			then(memberCharacterRepository).should(never()).grantIfAbsent(anyLong(), anyString(), anyString());
		}

		@Test
		@DisplayName("아직 닿지 않은 행성의 친구는 깨울 수 없다")
		void planetNotReached() {
			given(worldCommandService.requireWakeable(MEMBER_ID, "frog"))
				.willThrow(new WorldException(WorldErrorCode.WORLD_PLANET_NOT_REACHED));

			assertThatThrownBy(() -> friendCommandService.wakeWithCrumbs(MEMBER_ID, "frog"))
				.isInstanceOf(WorldException.class);
			then(crumbGrantService).shouldHaveNoInteractions();
		}
	}
}

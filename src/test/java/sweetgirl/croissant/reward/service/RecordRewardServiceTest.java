package sweetgirl.croissant.reward.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.reward.dto.response.RewardItemResponse;
import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.enums.RewardItemType;
import sweetgirl.croissant.reward.enums.RewardRefType;
import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.repository.CrumbLedgerRepository;
import sweetgirl.croissant.reward.service.GrantResult.Outcome;
import sweetgirl.croissant.world.service.WorldLevel;
import sweetgirl.croissant.world.service.WorldLevelReader;

@ExtendWith(MockitoExtension.class)
class RecordRewardServiceTest {

	private static final Long MEMBER_ID = 7L;
	private static final Long RECORD_ID = 55L;

	@Mock
	private CrumbGrantService crumbGrantService;
	@Mock
	private RewardPendingService rewardPendingService;
	@Mock
	private RewardSettings rewardSettings;
	@Mock
	private CrumbLedgerRepository ledgerRepository;
	@Mock
	private WorldLevelReader worldLevelReader;
	@Mock
	private InviteService inviteService;

	@InjectMocks
	private RecordRewardService recordRewardService;

	private Member member;

	@BeforeEach
	void setUp() {
		member = Member.registering("청순한 포카치아", LocalDateTime.of(2026, 9, 1, 12, 0));
		ReflectionTestUtils.setField(member, "id", MEMBER_ID);
		lenient().when(rewardSettings.get(RewardSettingKey.RECORD)).thenReturn(10);
		lenient().when(rewardSettings.get(RewardSettingKey.RECORD_PHOTO)).thenReturn(5);
		lenient().when(rewardSettings.get(RewardSettingKey.RECORD_DAILY_CAP)).thenReturn(3);
	}

	private void givenRecordGrant(GrantResult result) {
		given(crumbGrantService.grantWithDailyCap(
			eq(7L), eq(CrumbReason.RECORD), eq(10), eq("record:55"), eq(RewardRefType.RECORD), eq(55L), eq(3), any()))
			.willReturn(result);
	}

	private void givenPhotoGrant(GrantResult result) {
		given(crumbGrantService.grant(7L, CrumbReason.RECORD_PHOTO, 5, "record_photo:55", RewardRefType.RECORD, 55L))
			.willReturn(result);
	}

	private void givenLevels(int heightBefore, WorldLevel before, int heightAfter, WorldLevel after) {
		given(worldLevelReader.levelAt(MEMBER_ID, heightBefore)).willReturn(before);
		given(worldLevelReader.levelAt(MEMBER_ID, heightAfter)).willReturn(after);
	}

	@Nested
	@DisplayName("새 기록")
	class RewardCreated {

		@Test
		@DisplayName("사진 있는 첫 기록 — 빵 칸, +10, +5, 레벨업 순서로 싣는다")
		void firstRecordWithPhoto() {
			givenRecordGrant(new GrantResult(Outcome.GRANTED, 10, 0, 10));
			givenPhotoGrant(new GrantResult(Outcome.GRANTED, 5, 10, 15));
			givenLevels(0, new WorldLevel(1, 0, 1, "earth", "village"), 1, new WorldLevel(2, 1, 5, "earth", "sky"));

			List<RewardItemResponse> rewards = recordRewardService.rewardCreated(member, RECORD_ID, 2, 0, 1);

			assertThat(rewards).extracting(RewardItemResponse::type).containsExactly(
				RewardItemType.BREAD, RewardItemType.CRUMBS, RewardItemType.CRUMBS, RewardItemType.LEVEL_UP);
			assertThat(rewards.get(0)).isEqualTo(RewardItemResponse.bread(0, 1));
			assertThat(rewards.get(1).reason()).isEqualTo(CrumbReason.RECORD);
			assertThat(rewards.get(1).amount()).isEqualTo(10);
			assertThat(rewards.get(2).reason()).isEqualTo(CrumbReason.RECORD_PHOTO);
			assertThat(rewards.get(2).to()).isEqualTo(15);
			assertThat(rewards.get(3)).isEqualTo(RewardItemResponse.levelUp(1, 2, "earth", "sky"));
		}

		@Test
		@DisplayName("레벨업은 앱이 못 보고 꺼졌을 때를 위해 따로도 남긴다")
		void levelUpIsKeptAsPending() {
			givenRecordGrant(new GrantResult(Outcome.GRANTED, 10, 0, 10));
			givenLevels(9, new WorldLevel(3, 5, 10, "earth", "space"), 10,
				new WorldLevel(4, 10, 20, "mars", "village"));

			recordRewardService.rewardCreated(member, RECORD_ID, 0, 9, 10);

			then(rewardPendingService).should()
				.add(MEMBER_ID, "level_up:4", RewardItemResponse.levelUp(3, 4, "mars", "village"));
		}

		@Test
		@DisplayName("연속 기록 — 기록 보상을 받았으면 같은 만큼(+10)을 앞 기록 기준으로 한 번 더 준다")
		void chainedRecordGetsDouble() {
			givenRecordGrant(new GrantResult(Outcome.GRANTED, 10, 20, 30));
			given(
				crumbGrantService.grant(7L, CrumbReason.RECORD_CHAIN, 10, "record_chain:44", RewardRefType.RECORD, 55L))
				.willReturn(new GrantResult(Outcome.GRANTED, 10, 30, 40));
			givenLevels(2, new WorldLevel(2, 1, 5, "earth", "sky"), 3, new WorldLevel(2, 1, 5, "earth", "sky"));

			List<RewardItemResponse> rewards = recordRewardService.rewardChainedCreated(member, RECORD_ID, 0, 2, 3,
				44L);

			assertThat(rewards).extracting(RewardItemResponse::reason)
				.containsExactly(null, CrumbReason.RECORD, CrumbReason.RECORD_CHAIN);
			assertThat(rewards.get(2).amount()).isEqualTo(10);
		}

		@Test
		@DisplayName("연속 기록이어도 하루 상한에 걸린 기록은 2배 보너스가 없다")
		void chainedButCappedGetsNoBonus() {
			givenRecordGrant(new GrantResult(Outcome.CAPPED, 0, 30, 30));
			givenLevels(3, new WorldLevel(2, 1, 5, "earth", "sky"), 4, new WorldLevel(2, 1, 5, "earth", "sky"));

			recordRewardService.rewardChainedCreated(member, RECORD_ID, 0, 3, 4, 44L);

			then(crumbGrantService).should(never()).grant(anyLong(), eq(CrumbReason.RECORD_CHAIN), anyInt(),
				anyString(), any(), any());
		}

		@Test
		@DisplayName("사진이 없으면 +10 만 받는다")
		void withoutPhoto() {
			givenRecordGrant(new GrantResult(Outcome.GRANTED, 10, 20, 30));
			givenLevels(2, new WorldLevel(2, 1, 5, "earth", "sky"), 3, new WorldLevel(2, 1, 5, "earth", "sky"));

			List<RewardItemResponse> rewards = recordRewardService.rewardCreated(member, RECORD_ID, 0, 2, 3);

			assertThat(rewards).extracting(RewardItemResponse::type)
				.containsExactly(RewardItemType.BREAD, RewardItemType.CRUMBS);
			then(crumbGrantService).should(never()).grant(anyLong(), any(), anyInt(), anyString(), any(), any());
			then(rewardPendingService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("하루 상한을 넘은 기록은 빵 칸만 오르고 부스러기는 capped — 사진이 있어도 보너스가 없다")
		void capped() {
			givenRecordGrant(new GrantResult(Outcome.CAPPED, 0, 30, 30));
			givenLevels(3, new WorldLevel(2, 1, 5, "earth", "sky"), 4, new WorldLevel(2, 1, 5, "earth", "sky"));

			List<RewardItemResponse> rewards = recordRewardService.rewardCreated(member, RECORD_ID, 1, 3, 4);

			assertThat(rewards).hasSize(2);
			assertThat(rewards.get(1).capped()).isTrue();
			assertThat(rewards.get(1).amount()).isZero();
			then(crumbGrantService).should(never()).grant(anyLong(), any(), anyInt(), anyString(), any(), any());
		}

		@Test
		@DisplayName("지우고 다시 쓴 기록은 빵 칸도 레벨도 오르지 않지만 부스러기는 새 기록으로 받는다")
		void rewrittenRecord() {
			givenRecordGrant(new GrantResult(Outcome.GRANTED, 10, 30, 40));
			givenPhotoGrant(new GrantResult(Outcome.GRANTED, 5, 40, 45));

			List<RewardItemResponse> rewards = recordRewardService.rewardCreated(member, RECORD_ID, 1, 5, 5);

			assertThat(rewards).extracting(RewardItemResponse::type)
				.containsExactly(RewardItemType.CRUMBS, RewardItemType.CRUMBS);
			then(worldLevelReader).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("초대받은 회원의 첫 빵이면 초대 보상을 기록 보상 뒤, 레벨업 앞에 싣는다")
		void invitedMemberFirstRecord() {
			givenRecordGrant(new GrantResult(Outcome.GRANTED, 10, 0, 10));
			givenLevels(0, new WorldLevel(1, 0, 1, "earth", "village"), 1, new WorldLevel(2, 1, 5, "earth", "sky"));
			RewardItemResponse invited = RewardItemResponse.crumbs(
				new GrantResult(Outcome.GRANTED, 100, 10, 110), CrumbReason.INVITE_INVITEE);
			given(inviteService.rewardFirstRecord(member)).willReturn(List.of(invited));

			List<RewardItemResponse> rewards = recordRewardService.rewardCreated(member, RECORD_ID, 0, 0, 1);

			assertThat(rewards).extracting(RewardItemResponse::type).containsExactly(
				RewardItemType.BREAD, RewardItemType.CRUMBS, RewardItemType.CRUMBS, RewardItemType.LEVEL_UP);
			assertThat(rewards.get(2)).isEqualTo(invited);
		}

		@Test
		@DisplayName("빵투어의 가벼운 기록은 (빵투어, 가게)를 멱등 키로 쓰고, 그것도 초대를 성사시키는 첫 빵이다")
		void tourRecordUsesTourKey() {
			given(crumbGrantService.grantWithDailyCap(eq(7L), eq(CrumbReason.RECORD), eq(10),
				eq("tour_record:812:10"), eq(RewardRefType.RECORD), eq(55L), eq(3), any()))
				.willReturn(new GrantResult(Outcome.GRANTED, 10, 0, 10));

			List<RewardItemResponse> rewards = recordRewardService.rewardTourRecordCreated(
				member, RECORD_ID, 812L, 10L, 5, 5);

			assertThat(rewards).singleElement().satisfies(reward -> assertThat(reward.amount()).isEqualTo(10));
			then(inviteService).should().rewardFirstRecord(member);
		}

		@Test
		@DisplayName("게스트는 보상이 없다 — 지갑도 건드리지 않는다")
		void guest() {
			member.markAsGuest();

			assertThat(recordRewardService.rewardCreated(member, RECORD_ID, 1, 0, 1)).isEmpty();
			then(crumbGrantService).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("나중에 붙인 사진")
	class RewardPhotosAttached {

		private void givenRecordWasRewarded(boolean rewarded) {
			given(ledgerRepository.existsByMemberIdAndReasonAndRefTypeAndRefId(
				MEMBER_ID, CrumbReason.RECORD, RewardRefType.RECORD, RECORD_ID)).willReturn(rewarded);
		}

		@Test
		@DisplayName("기록 보상을 받은 기록에 7 일 안에 사진을 붙이면 +5")
		void withinSevenDays() {
			givenRecordWasRewarded(true);
			givenPhotoGrant(new GrantResult(Outcome.GRANTED, 5, 10, 15));

			List<RewardItemResponse> rewards = recordRewardService.rewardPhotosAttached(
				member, RECORD_ID, LocalDateTime.now().minusDays(6), 1);

			assertThat(rewards).singleElement()
				.satisfies(reward -> assertThat(reward.reason()).isEqualTo(CrumbReason.RECORD_PHOTO));
		}

		@Test
		@DisplayName("8 일째에 붙이면 주지 않는다")
		void afterSevenDays() {
			assertThat(recordRewardService.rewardPhotosAttached(
				member, RECORD_ID, LocalDateTime.now().minusDays(8), 1)).isEmpty();
			then(crumbGrantService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("이미 받은 기록은 사진을 지웠다 다시 붙여도 또 주지 않는다")
		void alreadyGranted() {
			givenRecordWasRewarded(true);
			givenPhotoGrant(new GrantResult(Outcome.ALREADY_GRANTED, 0, 15, 15));

			assertThat(recordRewardService.rewardPhotosAttached(
				member, RECORD_ID, LocalDateTime.now().minusDays(1), 2)).isEmpty();
		}

		@Test
		@DisplayName("상한에 걸려 기록 보상을 못 받은 기록은 사진을 붙여도 없다")
		void recordWasNotRewarded() {
			givenRecordWasRewarded(false);

			assertThat(recordRewardService.rewardPhotosAttached(
				member, RECORD_ID, LocalDateTime.now().minusDays(1), 1)).isEmpty();
			then(crumbGrantService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("사진이 여전히 없으면 원장도 읽지 않는다")
		void stillNoPhoto() {
			assertThat(recordRewardService.rewardPhotosAttached(
				member, RECORD_ID, LocalDateTime.now(), 0)).isEmpty();
			then(ledgerRepository).shouldHaveNoInteractions();
		}
	}
}

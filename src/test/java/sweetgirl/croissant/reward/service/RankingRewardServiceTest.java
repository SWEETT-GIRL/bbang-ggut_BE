package sweetgirl.croissant.reward.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.ranking.repository.RankingSnapshotJdbcRepository;
import sweetgirl.croissant.reward.code.RewardErrorCode;
import sweetgirl.croissant.reward.dto.response.RewardClaimResponse;
import sweetgirl.croissant.reward.entity.RankingWeeklyReward;
import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.enums.RewardRefType;
import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.exception.RewardException;
import sweetgirl.croissant.reward.repository.RankingWeeklyRewardRepository;
import sweetgirl.croissant.reward.service.GrantResult.Outcome;

@ExtendWith(MockitoExtension.class)
class RankingRewardServiceTest {

	/** 월요일. 지난주는 10/5(월) ~ 10/11(일)이다. */
	private static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);
	private static final LocalDate LAST_WEEK = LocalDate.of(2026, 10, 5);
	private static final LocalDate LAST_SUNDAY = LocalDate.of(2026, 10, 11);
	private static final Long MEMBER_ID = 7L;

	@Mock
	private RankingWeeklyRewardRepository rewardRepository;
	@Mock
	private RankingSnapshotJdbcRepository snapshotRepository;
	@Mock
	private MemberRestrictionReader memberRestrictionReader;
	@Mock
	private CrumbGrantService crumbGrantService;
	@Mock
	private RewardSettings rewardSettings;
	@Mock
	private MemberReader memberReader;

	@InjectMocks
	private RankingRewardService rankingRewardService;

	@BeforeEach
	void rules() {
		lenient().when(rewardSettings.get(RewardSettingKey.RANKING_FIRST)).thenReturn(100);
		lenient().when(rewardSettings.get(RewardSettingKey.RANKING_SECOND_THIRD)).thenReturn(50);
		lenient().when(rewardSettings.get(RewardSettingKey.RANKING_FOURTH_TENTH)).thenReturn(20);
	}

	@Nested
	@DisplayName("지난주 보상 만들기")
	class IssueFor {

		private void givenRanks(int count) {
			Map<Long, Integer> ranks = new LinkedHashMap<>();
			for (int rank = 1; rank <= count; rank++) {
				ranks.put(100L + rank, rank);
			}
			given(snapshotRepository.existsForDate(LAST_SUNDAY)).willReturn(true);
			given(snapshotRepository.findRanks(LAST_SUNDAY, "WEEKLY", 0, "")).willReturn(ranks);
			lenient().when(rewardRepository.insertIfAbsent(any(), anyLong(), anyInt(), anyInt(), any(), any()))
				.thenReturn(1);
		}

		@Test
		@DisplayName("12 명 순위에서 10 위까지만 — 1 위 100 + 왕관, 2·3 위 50, 4~10 위 20")
		void topTen() {
			givenRanks(12);

			assertThat(rankingRewardService.issueFor(MONDAY)).isEqualTo(10);

			then(rewardRepository).should().insertIfAbsent(
				eq(LAST_WEEK), eq(101L), eq(1), eq(100), eq(LAST_WEEK.plusDays(13)), any());
			then(rewardRepository).should().insertIfAbsent(eq(LAST_WEEK), eq(102L), eq(2), eq(50), isNull(), any());
			then(rewardRepository).should().insertIfAbsent(eq(LAST_WEEK), eq(103L), eq(3), eq(50), isNull(), any());
			then(rewardRepository).should().insertIfAbsent(eq(LAST_WEEK), eq(104L), eq(4), eq(20), isNull(), any());
			then(rewardRepository).should().insertIfAbsent(eq(LAST_WEEK), eq(110L), eq(10), eq(20), isNull(), any());
			then(rewardRepository).should(never()).insertIfAbsent(any(), eq(111L), anyInt(), anyInt(), any(), any());
		}

		@Test
		@DisplayName("주중 아무 날에 돌려도 그 주의 지난주(월~일)를 본다")
		void anyDayOfWeek() {
			givenRanks(1);

			rankingRewardService.issueFor(MONDAY.plusDays(3));

			then(rewardRepository).should().insertIfAbsent(eq(LAST_WEEK), eq(101L), eq(1), eq(100), any(), any());
		}

		@Test
		@DisplayName("제재 중인 회원은 건너뛰고 그 자리는 비운다 — 다음 순위를 끌어올리지 않는다")
		void skipsRestricted() {
			givenRanks(3);
			lenient().when(memberRestrictionReader.isWriteRestricted(102L)).thenReturn(true);

			assertThat(rankingRewardService.issueFor(MONDAY)).isEqualTo(2);

			then(rewardRepository).should(never()).insertIfAbsent(any(), eq(102L), anyInt(), anyInt(), any(), any());
			then(rewardRepository).should().insertIfAbsent(eq(LAST_WEEK), eq(103L), eq(3), eq(50), isNull(), any());
		}

		@Test
		@DisplayName("두 번 돌려도 줄이 늘지 않는다 — 이미 있으면 0 줄이다")
		void idempotent() {
			givenRanks(10);
			given(rewardRepository.insertIfAbsent(any(), anyLong(), anyInt(), anyInt(), any(), any())).willReturn(0);

			assertThat(rankingRewardService.issueFor(MONDAY)).isZero();
		}

		@Test
		@DisplayName("일요일 스냅샷이 아직 없으면 만들지 않고 -1 로 답한다")
		void snapshotMissing() {
			given(snapshotRepository.existsForDate(LAST_SUNDAY)).willReturn(false);

			assertThat(rankingRewardService.issueFor(MONDAY)).isEqualTo(-1);
			then(rewardRepository).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("받기")
	class Claim {

		private RankingWeeklyReward reward(LocalDate weekStart, boolean claimed) {
			RankingWeeklyReward reward = mock(RankingWeeklyReward.class);
			lenient().when(reward.getWeekStart()).thenReturn(weekStart);
			lenient().when(reward.getCrumbs()).thenReturn(50);
			lenient().when(reward.isClaimed()).thenReturn(claimed);
			lenient().when(reward.lastClaimableDay()).thenReturn(weekStart.plusDays(34));
			given(rewardRepository.findByIdAndMemberId(12L, MEMBER_ID)).willReturn(Optional.of(reward));
			return reward;
		}

		@Test
		@DisplayName("받으면 그 줄의 부스러기가 들어온다")
		void claims() {
			LocalDate weekStart = LocalDate.now(CrumbGrantService.ZONE).minusDays(8);
			reward(weekStart, false);
			given(rewardRepository.claim(eq(12L), eq(MEMBER_ID), any(), any())).willReturn(1);
			given(crumbGrantService.grant(7L, CrumbReason.RANKING, 50, "rank:" + weekStart, RewardRefType.RANKING, 12L))
				.willReturn(new GrantResult(Outcome.GRANTED, 50, 10, 60));

			RewardClaimResponse response = rankingRewardService.claim(MEMBER_ID, 12L);

			assertThat(response.alreadyDone()).isFalse();
			assertThat(response.rewards()).singleElement().satisfies(item -> {
				assertThat(item.amount()).isEqualTo(50);
				assertThat(item.reason()).isEqualTo(CrumbReason.RANKING);
			});
		}

		@Test
		@DisplayName("이미 받은 것을 다시 누르면 alreadyDone 이고 또 주지 않는다")
		void alreadyClaimed() {
			reward(LocalDate.now(CrumbGrantService.ZONE).minusDays(8), true);

			assertThat(rankingRewardService.claim(MEMBER_ID, 12L)).isEqualTo(RewardClaimResponse.done());
			then(crumbGrantService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("4 주가 지난 보상은 REWARD410_1")
		void expired() {
			reward(LocalDate.now(CrumbGrantService.ZONE).minusDays(36), false);
			given(rewardRepository.claim(eq(12L), eq(MEMBER_ID), any(), any())).willReturn(0);

			assertThatThrownBy(() -> rankingRewardService.claim(MEMBER_ID, 12L))
				.isInstanceOf(RewardException.class)
				.extracting("errorCode").isEqualTo(RewardErrorCode.REWARD_RANKING_REWARD_EXPIRED);
			then(crumbGrantService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("남의 보상은 REWARD404_5")
		void notMine() {
			given(rewardRepository.findByIdAndMemberId(12L, MEMBER_ID)).willReturn(Optional.empty());

			assertThatThrownBy(() -> rankingRewardService.claim(MEMBER_ID, 12L))
				.isInstanceOf(RewardException.class)
				.extracting("errorCode").isEqualTo(RewardErrorCode.REWARD_RANKING_REWARD_NOT_FOUND);
		}

		@Test
		@DisplayName("받기가 나란히 들어와 다른 쪽이 먼저 받았으면 이쪽은 주지 않는다")
		void lostTheRace() {
			reward(LocalDate.now(CrumbGrantService.ZONE).minusDays(8), false);
			given(rewardRepository.claim(eq(12L), eq(MEMBER_ID), any(), any())).willReturn(0);

			assertThat(rankingRewardService.claim(MEMBER_ID, 12L)).isEqualTo(RewardClaimResponse.done());
			then(crumbGrantService).shouldHaveNoInteractions();
		}
	}

	@Test
	@DisplayName("받을 수 있는 기한은 받을 수 있게 된 월요일부터 4 주다 — 겨룬 주의 월요일에서 34 일째까지")
	void claimableWindow() {
		given(memberReader.getMember(MEMBER_ID)).willReturn(mock(Member.class));
		given(rewardRepository.findClaimable(eq(MEMBER_ID), any())).willReturn(List.of());

		rankingRewardService.getPending(MEMBER_ID);

		then(rewardRepository).should()
			.findClaimable(MEMBER_ID, LocalDate.now(CrumbGrantService.ZONE).minusDays(34));
	}
}

package sweetgirl.croissant.reward.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;

import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.reward.dto.response.HomeCardResponse;
import sweetgirl.croissant.reward.dto.response.RankingRewardResponse;
import sweetgirl.croissant.reward.dto.response.RewardHistoryResponse;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse.Ad;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse.Attendance;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse.CarryOver;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse.Feed;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse.Letter;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse.RankingReward;
import sweetgirl.croissant.reward.entity.CrumbLedgerEntry;
import sweetgirl.croissant.reward.entity.MemberAttendanceDay;
import sweetgirl.croissant.reward.entity.MemberFeedDay;
import sweetgirl.croissant.reward.repository.CrumbLedgerRepository;
import sweetgirl.croissant.reward.repository.MemberAttendanceDayRepository;
import sweetgirl.croissant.reward.repository.MemberCrumbWalletRepository;
import sweetgirl.croissant.reward.repository.MemberFeedDayRepository;

@ExtendWith(MockitoExtension.class)
class RewardQueryServiceTest {

	private static final Long MEMBER_ID = 7L;

	@Mock
	private MemberCrumbWalletRepository walletRepository;
	@Mock
	private CrumbLedgerRepository ledgerRepository;
	@Mock
	private RewardPendingService rewardPendingService;
	@Mock
	private RewardSettings rewardSettings;
	@Mock
	private RewardMemberPolicy rewardMemberPolicy;
	@Mock
	private MemberReader memberReader;
	@Mock
	private MemberAttendanceDayRepository attendanceDayRepository;
	@Mock
	private MemberFeedDayRepository feedDayRepository;
	@Mock
	private BbangdaengLetterService bbangdaengLetterService;
	@Mock
	private EarnRewardService earnRewardService;
	@Mock
	private RankingRewardService rankingRewardService;
	@Mock
	private HomeCardService homeCardService;

	@InjectMocks
	private RewardQueryService rewardQueryService;

	private Member member;

	@BeforeEach
	void setUp() {
		member = mock(Member.class);
		given(memberReader.getMember(MEMBER_ID)).willReturn(member);
		// 내역 조회는 수치를 읽지 않는다
		lenient().when(rewardSettings.attendance()).thenReturn(List.of(5, 5, 10, 5, 10, 10, 30));
		lenient().when(rewardSettings.feedWedges()).thenReturn(List.of(10, 5, 3, 5, 10, 50));
	}

	@Nested
	@DisplayName("요약")
	class GetSummary {

		@Test
		@DisplayName("회원은 잔액 · 오늘 받은 양 · 방금 들어간 이관 선물을 본다")
		void member() {
			given(walletRepository.findBalance(MEMBER_ID)).willReturn(Optional.of(285));
			given(ledgerRepository.sumGainedOn(eq(MEMBER_ID), any())).willReturn(26L);
			given(rewardMemberPolicy.isWelcomeTarget(member)).willReturn(false);
			given(rewardPendingService.list(MEMBER_ID)).willReturn(List.of());
			CarryOver carryOver = new CarryOver(true, 110, 12);

			RewardSummaryResponse summary = rewardQueryService.getSummary(MEMBER_ID, carryOver);

			assertThat(summary.crumbs()).isEqualTo(285);
			assertThat(summary.gainedToday()).isEqualTo(26);
			assertThat(summary.carryOver()).isEqualTo(carryOver);
			assertThat(summary.welcome().available()).isFalse();
		}

		@Test
		@DisplayName("아직 환영 선물을 안 받은 새 회원은 welcome.available 이 true 다")
		void welcomeAvailable() {
			given(rewardMemberPolicy.isWelcomeTarget(member)).willReturn(true);
			given(ledgerRepository.existsByMemberIdAndIdempotencyKey(MEMBER_ID, "welcome")).willReturn(false);

			RewardSummaryResponse summary = rewardQueryService.getSummary(MEMBER_ID, null);

			assertThat(summary.welcome().available()).isTrue();
			assertThat(summary.crumbs()).isZero();
			assertThat(summary.carryOver()).isNull();
		}

		@Test
		@DisplayName("환영 선물을 받은 뒤에는 available 이 false 다")
		void welcomeTaken() {
			given(rewardMemberPolicy.isWelcomeTarget(member)).willReturn(true);
			given(ledgerRepository.existsByMemberIdAndIdempotencyKey(MEMBER_ID, "welcome")).willReturn(true);

			assertThat(rewardQueryService.getSummary(MEMBER_ID, null).welcome().available()).isFalse();
		}

		@Test
		@DisplayName("게스트는 에러가 아니라 빈 요약을 받는다 — 지갑도 원장도 읽지 않는다")
		void guest() {
			given(member.isGuest()).willReturn(true);

			RewardSummaryResponse summary = rewardQueryService.getSummary(MEMBER_ID, null);

			assertThat(summary.crumbs()).isZero();
			assertThat(summary.welcome().available()).isFalse();
			assertThat(summary.pendingRewards()).isEmpty();
			assertThat(summary.rules()).isNotNull();
			then(walletRepository).shouldHaveNoInteractions();
			then(ledgerRepository).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("요약의 편지 · 광고")
	class LetterAndAd {

		@Test
		@DisplayName("안 연 편지 수와 오늘 남은 광고 횟수를 싣는다")
		void member() {
			given(bbangdaengLetterService.summaryOf(MEMBER_ID)).willReturn(new Letter(2, 31L));
			given(earnRewardService.adsLeftToday(MEMBER_ID)).willReturn(1);

			RewardSummaryResponse summary = rewardQueryService.getSummary(MEMBER_ID, null);

			assertThat(summary.letter()).isEqualTo(new Letter(2, 31L));
			assertThat(summary.ad()).isEqualTo(new Ad(1));
		}

		@Test
		@DisplayName("받을 랭킹 주간 보상이 있으면 가장 최근 주의 것을 싣고, 없으면 null 이다")
		void rankingReward() {
			given(rankingRewardService.latestPending(MEMBER_ID)).willReturn(Optional.of(new RankingRewardResponse(
				12L, LocalDate.of(2026, 10, 5), 2, 50, false, LocalDate.of(2026, 11, 8))));

			assertThat(rewardQueryService.getSummary(MEMBER_ID, null).rankingReward())
				.isEqualTo(new RankingReward(12L, 2, 50));

			given(rankingRewardService.latestPending(MEMBER_ID)).willReturn(Optional.empty());
			assertThat(rewardQueryService.getSummary(MEMBER_ID, null).rankingReward()).isNull();
		}

		@Test
		@DisplayName("이미 읽은 편지 · 밥 상태를 넘겨 할 일 카드를 고르고, 고른 카드를 싣는다")
		void homeCard() {
			given(bbangdaengLetterService.summaryOf(MEMBER_ID)).willReturn(new Letter(1, 31L));
			given(feedDayRepository.findByMemberIdAndDay(eq(MEMBER_ID), any()))
				.willReturn(Optional.of(mock(MemberFeedDay.class)));
			given(homeCardService.cardFor(eq(MEMBER_ID), eq(new Letter(1, 31L)), eq(true), any()))
				.willReturn(Optional.of(HomeCardResponse.letter(31L)));

			assertThat(rewardQueryService.getSummary(MEMBER_ID, null).homeCard())
				.isEqualTo(HomeCardResponse.letter(31L));
		}

		@Test
		@DisplayName("게스트는 편지도 광고도 없다")
		void guest() {
			given(member.isGuest()).willReturn(true);

			RewardSummaryResponse summary = rewardQueryService.getSummary(MEMBER_ID, null);

			assertThat(summary.letter()).isEqualTo(new Letter(0, null));
			assertThat(summary.ad()).isEqualTo(new Ad(0));
			assertThat(summary.homeCard()).isNull();
			then(bbangdaengLetterService).shouldHaveNoInteractions();
			then(homeCardService).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("요약의 출석 · 밥")
	class DailyBlocks {

		private MemberAttendanceDay attended(int boardDay) {
			MemberAttendanceDay day = mock(MemberAttendanceDay.class);
			// 오늘 행이 있으면 어제 행의 칸은 읽히지 않는다
			lenient().when(day.getBoardDay()).thenReturn(boardDay);
			return day;
		}

		@Test
		@DisplayName("어제 2 일째까지 찍었고 오늘 안 했으면 — 찍힌 칸 2, 오늘 3 일째 10, 내일 5")
		void continuing() {
			MemberAttendanceDay yesterday = attended(2);
			given(attendanceDayRepository.findByMemberIdAndDay(eq(MEMBER_ID), any()))
				.willReturn(Optional.of(yesterday), Optional.empty());

			Attendance attendance = rewardQueryService.getSummary(MEMBER_ID, null).attendance();

			assertThat(attendance).isEqualTo(new Attendance(false, 2, 3, 10, 5));
		}

		@Test
		@DisplayName("오늘 7 일째를 찍었으면 — 찍힌 칸 7, 오늘 30, 내일은 1 일째 5")
		void finishedBoardToday() {
			MemberAttendanceDay yesterday = attended(6);
			MemberAttendanceDay today = attended(7);
			given(attendanceDayRepository.findByMemberIdAndDay(eq(MEMBER_ID), any()))
				.willReturn(Optional.of(yesterday), Optional.of(today));

			Attendance attendance = rewardQueryService.getSummary(MEMBER_ID, null).attendance();

			assertThat(attendance).isEqualTo(new Attendance(true, 7, 1, 30, 5));
		}

		@Test
		@DisplayName("어제 7 일째를 채웠으면 오늘은 빈 판의 1 일째다")
		void newBoardAfterSeventh() {
			MemberAttendanceDay yesterday = attended(7);
			given(attendanceDayRepository.findByMemberIdAndDay(eq(MEMBER_ID), any()))
				.willReturn(Optional.of(yesterday), Optional.empty());

			Attendance attendance = rewardQueryService.getSummary(MEMBER_ID, null).attendance();

			assertThat(attendance).isEqualTo(new Attendance(false, 0, 1, 5, 5));
		}

		@Test
		@DisplayName("어제 안 했으면 끊겨서 1 일째다")
		void broken() {
			given(attendanceDayRepository.findByMemberIdAndDay(eq(MEMBER_ID), any())).willReturn(Optional.empty());

			Attendance attendance = rewardQueryService.getSummary(MEMBER_ID, null).attendance();

			assertThat(attendance).isEqualTo(new Attendance(false, 0, 1, 5, 5));
		}

		@Test
		@DisplayName("오늘 밥을 줬으면 doneToday 이고 칸 배열은 그대로 내린다")
		void fedToday() {
			given(attendanceDayRepository.findByMemberIdAndDay(eq(MEMBER_ID), any())).willReturn(Optional.empty());
			given(feedDayRepository.findByMemberIdAndDay(eq(MEMBER_ID), any()))
				.willReturn(Optional.of(mock(MemberFeedDay.class)));

			Feed feed = rewardQueryService.getSummary(MEMBER_ID, null).feed();

			assertThat(feed).isEqualTo(new Feed(true, List.of(10, 5, 3, 5, 10, 50)));
		}

		@Test
		@DisplayName("게스트는 빈 판과 안 돌린 룰렛을 받는다 — 출석 표를 읽지 않는다")
		void guest() {
			given(member.isGuest()).willReturn(true);

			RewardSummaryResponse summary = rewardQueryService.getSummary(MEMBER_ID, null);

			assertThat(summary.attendance()).isEqualTo(new Attendance(false, 0, 1, 5, 5));
			assertThat(summary.feed().doneToday()).isFalse();
			then(attendanceDayRepository).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("받은 내역")
	class GetHistory {

		private List<CrumbLedgerEntry> entries(long newestId, int count) {
			List<CrumbLedgerEntry> entries = new ArrayList<>();
			for (int index = 0; index < count; index++) {
				// 한 장 더 읽은 마지막 줄은 응답에 안 실려 id 를 읽히지 않는다
				CrumbLedgerEntry entry = mock(CrumbLedgerEntry.class, withSettings().strictness(Strictness.LENIENT));
				given(entry.getId()).willReturn(newestId - index);
				entries.add(entry);
			}
			return entries;
		}

		@Test
		@DisplayName("한 장을 넘게 읽히면 다음 장 커서를 준다")
		void hasNextPage() {
			List<CrumbLedgerEntry> threeEntries = entries(100, 3);
			given(ledgerRepository.findByMemberIdOrderByIdDesc(MEMBER_ID, PageRequest.of(0, 3)))
				.willReturn(threeEntries);

			RewardHistoryResponse history = rewardQueryService.getHistory(MEMBER_ID, null, 2);

			assertThat(history.items()).extracting(RewardHistoryResponse.Item::id).containsExactly(100L, 99L);
			assertThat(history.nextCursor()).isEqualTo(99L);
		}

		@Test
		@DisplayName("마지막 장이면 커서가 없다")
		void lastPage() {
			List<CrumbLedgerEntry> twoEntries = entries(98, 2);
			given(ledgerRepository.findByMemberIdAndIdLessThanOrderByIdDesc(MEMBER_ID, 99L, PageRequest.of(0, 3)))
				.willReturn(twoEntries);

			RewardHistoryResponse history = rewardQueryService.getHistory(MEMBER_ID, 99L, 2);

			assertThat(history.items()).hasSize(2);
			assertThat(history.nextCursor()).isNull();
		}

		@Test
		@DisplayName("한 장은 50 개를 넘지 않는다")
		void clampsSize() {
			given(ledgerRepository.findByMemberIdOrderByIdDesc(MEMBER_ID, PageRequest.of(0, 51)))
				.willReturn(List.of());

			assertThat(rewardQueryService.getHistory(MEMBER_ID, null, 5000).items()).isEmpty();
		}

		@Test
		@DisplayName("게스트는 빈 내역이다")
		void guest() {
			given(member.isGuest()).willReturn(true);

			assertThat(rewardQueryService.getHistory(MEMBER_ID, null, 30).items()).isEmpty();
			then(ledgerRepository).shouldHaveNoInteractions();
		}
	}
}

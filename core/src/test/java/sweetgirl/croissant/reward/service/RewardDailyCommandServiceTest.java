package sweetgirl.croissant.reward.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.reward.dto.response.AttendanceResponse;
import sweetgirl.croissant.reward.dto.response.FeedResponse;
import sweetgirl.croissant.reward.entity.MemberAttendanceDay;
import sweetgirl.croissant.reward.entity.MemberFeedDay;
import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.repository.MemberAttendanceDayRepository;
import sweetgirl.croissant.reward.repository.MemberFeedDayRepository;
import sweetgirl.croissant.reward.service.GrantResult.Outcome;

@ExtendWith(MockitoExtension.class)
class RewardDailyCommandServiceTest {

	private static final Long MEMBER_ID = 7L;

	@Mock
	private CrumbGrantService crumbGrantService;
	@Mock
	private RewardSettings rewardSettings;
	@Mock
	private RewardRandom rewardRandom;
	@Mock
	private MemberAttendanceDayRepository attendanceDayRepository;
	@Mock
	private MemberFeedDayRepository feedDayRepository;
	@Mock
	private BreadRecordRepository breadRecordRepository;
	@Mock
	private MemberReader memberReader;

	@InjectMocks
	private RewardDailyCommandService rewardDailyCommandService;

	private final LocalDate today = LocalDate.now(CrumbGrantService.ZONE);

	@BeforeEach
	void rules() {
		lenient().when(rewardSettings.attendance()).thenReturn(List.of(5, 5, 10, 5, 10, 10, 30));
		lenient().when(rewardSettings.feedWedges()).thenReturn(List.of(10, 5, 3, 5, 10, 50));
		Map<Integer, Integer> weights = new LinkedHashMap<>();
		weights.put(3, 45);
		weights.put(5, 35);
		weights.put(10, 17);
		weights.put(50, 3);
		lenient().when(rewardSettings.feedWeights()).thenReturn(weights);
		lenient().when(rewardSettings.get(RewardSettingKey.FEED_AD_DAILY_CAP)).thenReturn(3);
	}

	private MemberAttendanceDay attended(int boardDay, int crumbs) {
		MemberAttendanceDay day = mock(MemberAttendanceDay.class);
		lenient().when(day.getBoardDay()).thenReturn(boardDay);
		lenient().when(day.getCrumbs()).thenReturn(crumbs);
		return day;
	}

	@Nested
	@DisplayName("출석")
	class CheckIn {

		private void givenYesterday(Optional<MemberAttendanceDay> yesterday) {
			given(attendanceDayRepository.findByMemberIdAndDay(MEMBER_ID, today.minusDays(1))).willReturn(yesterday);
		}

		private void givenGranted(int amount) {
			given(attendanceDayRepository.insertIfAbsent(eq(MEMBER_ID), eq(today), anyInt(), eq(amount), any()))
				.willReturn(1);
			given(crumbGrantService.grantOn(7L, CrumbReason.ATTENDANCE, amount, "attendance:" + today, today))
				.willReturn(new GrantResult(Outcome.GRANTED, amount, 0, amount));
		}

		@ParameterizedTest(name = "어제 {0}일째 → 오늘 {1}일째 +{2}")
		@CsvSource({"1, 2, 5", "2, 3, 10", "6, 7, 30", "7, 1, 5"})
		@DisplayName("어제에 이어지면 다음 칸이고, 7 일을 채운 다음 날은 1 일째다")
		void continues(int yesterdayBoardDay, int expectedBoardDay, int expectedReward) {
			MemberAttendanceDay yesterday = attended(yesterdayBoardDay, 5);
			givenYesterday(Optional.of(yesterday));
			givenGranted(expectedReward);

			AttendanceResponse response = rewardDailyCommandService.checkIn(MEMBER_ID);

			assertThat(response.boardDay()).isEqualTo(expectedBoardDay);
			assertThat(response.crumbs()).isEqualTo(expectedReward);
			assertThat(response.alreadyDone()).isFalse();
			assertThat(response.rewards()).singleElement()
				.satisfies(reward -> assertThat(reward.reason()).isEqualTo(CrumbReason.ATTENDANCE));
			then(attendanceDayRepository).should()
				.insertIfAbsent(eq(MEMBER_ID), eq(today), eq(expectedBoardDay), eq(expectedReward), any());
		}

		@Test
		@DisplayName("어제 안 했으면(하루라도 빠지면) 1 일째다")
		void broken() {
			givenYesterday(Optional.empty());
			givenGranted(5);

			assertThat(rewardDailyCommandService.checkIn(MEMBER_ID).boardDay()).isEqualTo(1);
		}

		@Test
		@DisplayName("7 일을 이어 출석하면 합이 75 다")
		void sevenDaysSum() {
			assertThat(rewardSettings.attendance().stream().mapToInt(Integer::intValue).sum()).isEqualTo(75);
		}

		@Test
		@DisplayName("오늘 이미 했으면 오늘 결과를 그대로 돌려주고 부스러기를 또 주지 않는다")
		void alreadyDone() {
			givenYesterday(Optional.empty());
			given(attendanceDayRepository.insertIfAbsent(eq(MEMBER_ID), eq(today), anyInt(), anyInt(), any()))
				.willReturn(0);
			MemberAttendanceDay done = attended(3, 10);
			given(attendanceDayRepository.findByMemberIdAndDay(MEMBER_ID, today)).willReturn(Optional.of(done));

			AttendanceResponse response = rewardDailyCommandService.checkIn(MEMBER_ID);

			assertThat(response).isEqualTo(new AttendanceResponse(3, 10, true, List.of()));
			then(crumbGrantService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("제재 중이면 도장은 찍히되 부스러기는 0 으로 남는다")
		void restricted() {
			givenYesterday(Optional.empty());
			given(attendanceDayRepository.insertIfAbsent(eq(MEMBER_ID), eq(today), eq(1), eq(5), any())).willReturn(1);
			given(crumbGrantService.grantOn(7L, CrumbReason.ATTENDANCE, 5, "attendance:" + today, today))
				.willReturn(new GrantResult(Outcome.RESTRICTED, 0, 0, 0));

			AttendanceResponse response = rewardDailyCommandService.checkIn(MEMBER_ID);

			assertThat(response).isEqualTo(new AttendanceResponse(1, 0, false, List.of()));
			then(attendanceDayRepository).should().updateCrumbs(MEMBER_ID, today, 0);
		}

		@Test
		@DisplayName("게스트는 403 이다")
		void guest() {
			willThrow(new MemberException(MemberErrorCode.GUEST_LOGIN_REQUIRED))
				.given(memberReader).requireLoggedIn(MEMBER_ID);

			assertThatThrownBy(() -> rewardDailyCommandService.checkIn(MEMBER_ID))
				.isInstanceOf(MemberException.class);
			then(attendanceDayRepository).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("밥 룰렛")
	class Feed {

		/** 첫 난수는 가중치 합(100) 안에서 값을 고르고, 둘째 난수는 그 값이 적힌 칸 중 하나를 고른다. */
		private void givenRolls(int valueRoll, int wedgeRoll) {
			given(rewardRandom.nextInt(100)).willReturn(valueRoll);
			lenient().when(rewardRandom.nextInt(1)).thenReturn(0);
			lenient().when(rewardRandom.nextInt(2)).thenReturn(wedgeRoll);
		}

		@ParameterizedTest(name = "난수 {0} · {1} → {2}번 칸 +{3}")
		@CsvSource({"0, 0, 2, 3", "44, 0, 2, 3", "45, 0, 1, 5", "45, 1, 3, 5", "79, 1, 3, 5",
			"80, 0, 0, 10", "96, 1, 4, 10", "97, 0, 5, 50", "99, 0, 5, 50"})
		@DisplayName("가중치 45 · 35 · 17 · 3 으로 값을 뽑고, 멈춘 칸의 값만큼 준다")
		void picksByWeight(int valueRoll, int wedgeRoll, int expectedWedge, int expectedReward) {
			givenRolls(valueRoll, wedgeRoll);
			// 광고로 더 돌릴 횟수는 하루 상한으로 시작한다
			given(feedDayRepository.insertIfAbsent(eq(MEMBER_ID), eq(today), eq(expectedWedge), eq(expectedReward),
				eq(3), any())).willReturn(1);
			given(crumbGrantService.grantOn(7L, CrumbReason.FEED, expectedReward, "feed:" + today, today))
				.willReturn(new GrantResult(Outcome.GRANTED, expectedReward, 0, expectedReward));

			FeedResponse response = rewardDailyCommandService.feed(MEMBER_ID);

			assertThat(response.wedge()).isEqualTo(expectedWedge);
			assertThat(response.crumbs()).isEqualTo(expectedReward);
			assertThat(rewardSettings.feedWedges().get(response.wedge())).isEqualTo(response.crumbs());
			assertThat(response.rewards()).singleElement()
				.satisfies(reward -> assertThat(reward.reason()).isEqualTo(CrumbReason.FEED));
		}

		@Test
		@DisplayName("오늘 이미 돌렸고 광고로 연 판도 없으면 새로 뽑은 칸이 아니라 오늘 결과를 돌려준다")
		void alreadyDone() {
			givenRolls(0, 0);
			given(feedDayRepository.insertIfAbsent(eq(MEMBER_ID), eq(today), anyInt(), anyInt(), anyInt(), any()))
				.willReturn(0);
			given(feedDayRepository.closeExtraIfReady(MEMBER_ID, today)).willReturn(0);
			MemberFeedDay done = mock(MemberFeedDay.class);
			given(done.getWedge()).willReturn(5);
			given(done.getCrumbs()).willReturn(50);
			given(feedDayRepository.findByMemberIdAndDay(MEMBER_ID, today)).willReturn(Optional.of(done));

			FeedResponse response = rewardDailyCommandService.feed(MEMBER_ID);

			assertThat(response).isEqualTo(new FeedResponse(5, 50, true, List.of()));
			then(crumbGrantService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("무료 판을 쓴 뒤라도 광고로 연 판이 있으면 한 판 더 돌고, 그 판은 닫힌다")
		void extraSpin() {
			givenRolls(97, 0);
			given(feedDayRepository.insertIfAbsent(eq(MEMBER_ID), eq(today), anyInt(), anyInt(), anyInt(), any()))
				.willReturn(0);
			given(feedDayRepository.closeExtraIfReady(MEMBER_ID, today)).willReturn(1);
			MemberFeedDay done = mock(MemberFeedDay.class);
			given(done.getExtraLeft()).willReturn(2);
			given(feedDayRepository.findByMemberIdAndDay(MEMBER_ID, today)).willReturn(Optional.of(done));
			// 멱등 키는 남은 횟수로 가른다 — 무료 판(feed:날짜)과도, 다음 광고 판과도 겹치지 않는다
			given(crumbGrantService.grantOn(7L, CrumbReason.FEED, 50, "feed:" + today + ":extra:2", today))
				.willReturn(new GrantResult(Outcome.GRANTED, 50, 30, 80));

			FeedResponse response = rewardDailyCommandService.feed(MEMBER_ID);

			assertThat(response.wedge()).isEqualTo(5);
			assertThat(response.crumbs()).isEqualTo(50);
			assertThat(response.alreadyDone()).isFalse();
			assertThat(response.rewards()).singleElement()
				.satisfies(reward -> assertThat(reward.reason()).isEqualTo(CrumbReason.FEED));
			// 무료 판의 결과(wedge · crumbs)는 그대로 둔다
			then(feedDayRepository).should(never()).updateCrumbs(anyLong(), any(), anyInt());
		}

		@Test
		@DisplayName("광고로 연 판도 제재 중이면 돌되 부스러기는 없다 — 무료 판의 결과는 건드리지 않는다")
		void extraSpinRestricted() {
			givenRolls(97, 0);
			given(feedDayRepository.insertIfAbsent(eq(MEMBER_ID), eq(today), anyInt(), anyInt(), anyInt(), any()))
				.willReturn(0);
			given(feedDayRepository.closeExtraIfReady(MEMBER_ID, today)).willReturn(1);
			MemberFeedDay done = mock(MemberFeedDay.class);
			given(done.getExtraLeft()).willReturn(0);
			given(feedDayRepository.findByMemberIdAndDay(MEMBER_ID, today)).willReturn(Optional.of(done));
			given(crumbGrantService.grantOn(7L, CrumbReason.FEED, 50, "feed:" + today + ":extra:0", today))
				.willReturn(new GrantResult(Outcome.RESTRICTED, 0, 0, 0));

			FeedResponse response = rewardDailyCommandService.feed(MEMBER_ID);

			assertThat(response).isEqualTo(new FeedResponse(5, 0, false, List.of()));
			then(feedDayRepository).should(never()).updateCrumbs(anyLong(), any(), anyInt());
		}

		@Test
		@DisplayName("광고를 끝까지 보면 한 판을 연다 — 열렸는지는 표가 답한다(횟수가 남고 열린 판이 없을 때만)")
		void openFeedExtra() {
			given(feedDayRepository.openExtraIfLeft(MEMBER_ID, today)).willReturn(1);

			assertThat(rewardDailyCommandService.openFeedExtra(MEMBER_ID)).isTrue();
		}

		@Test
		@DisplayName("횟수를 다 썼거나 이미 열린 판이 있거나 오늘 무료 판을 아직 안 돌렸으면 열지 않는다")
		void openFeedExtraNoneLeft() {
			given(feedDayRepository.openExtraIfLeft(MEMBER_ID, today)).willReturn(0);

			assertThat(rewardDailyCommandService.openFeedExtra(MEMBER_ID)).isFalse();
		}

		@Test
		@DisplayName("제재 중이면 룰렛은 돌되 부스러기는 0 으로 남는다")
		void restricted() {
			givenRolls(97, 0);
			given(feedDayRepository.insertIfAbsent(eq(MEMBER_ID), eq(today), eq(5), eq(50), eq(3), any()))
				.willReturn(1);
			given(crumbGrantService.grantOn(7L, CrumbReason.FEED, 50, "feed:" + today, today))
				.willReturn(new GrantResult(Outcome.RESTRICTED, 0, 0, 0));

			FeedResponse response = rewardDailyCommandService.feed(MEMBER_ID);

			assertThat(response).isEqualTo(new FeedResponse(5, 0, false, List.of()));
			then(feedDayRepository).should().updateCrumbs(MEMBER_ID, today, 0);
		}

		@Test
		@DisplayName("가중치가 가리키는 값이 판에 하나도 없으면 칸을 고르게 뽑는다 — 룰렛이 멈출 곳은 늘 있다")
		void fallsBackToUniformWedge() {
			given(rewardSettings.feedWedges()).willReturn(List.of(20, 20, 20, 20, 20, 20));
			given(rewardRandom.nextInt(6)).willReturn(4);
			given(feedDayRepository.insertIfAbsent(eq(MEMBER_ID), eq(today), eq(4), eq(20), eq(3), any()))
				.willReturn(1);
			given(crumbGrantService.grantOn(7L, CrumbReason.FEED, 20, "feed:" + today, today))
				.willReturn(new GrantResult(Outcome.GRANTED, 20, 0, 20));

			assertThat(rewardDailyCommandService.feed(MEMBER_ID).wedge()).isEqualTo(4);
		}

		@Test
		@DisplayName("게스트는 403 이다")
		void guest() {
			willThrow(new MemberException(MemberErrorCode.GUEST_LOGIN_REQUIRED))
				.given(memberReader).requireLoggedIn(MEMBER_ID);

			assertThatThrownBy(() -> rewardDailyCommandService.feed(MEMBER_ID)).isInstanceOf(MemberException.class);
			then(feedDayRepository).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("연속 기록 일수를 출석판 자리로")
	class SeedAttendanceFromRecordStreak {

		private void givenRecordedDaysAgo(int... daysAgo) {
			List<String> days = new ArrayList<>();
			for (int ago : daysAgo) {
				days.add(today.minusDays(ago).toString());
			}
			given(breadRecordRepository.findRecordedDaysSince(eq(MEMBER_ID), any())).willReturn(days);
		}

		@Test
		@DisplayName("어제까지 3 일 이어 썼으면 어제 날짜로 3 일째 칸을 0 으로 찍는다")
		void streakEndingYesterday() {
			givenRecordedDaysAgo(1, 2, 3, 6);

			rewardDailyCommandService.seedAttendanceFromRecordStreak(MEMBER_ID, today);

			then(attendanceDayRepository).should()
				.insertIfAbsent(eq(MEMBER_ID), eq(today.minusDays(1)), eq(3), eq(0), any());
		}

		@Test
		@DisplayName("오늘까지 이어진 연속도 센다 — 9 일 연속이면 2 일째 자리(7 로 나눈 나머지)")
		void streakIncludingTodayWraps() {
			givenRecordedDaysAgo(0, 1, 2, 3, 4, 5, 6, 7, 8);

			rewardDailyCommandService.seedAttendanceFromRecordStreak(MEMBER_ID, today);

			then(attendanceDayRepository).should()
				.insertIfAbsent(eq(MEMBER_ID), eq(today.minusDays(1)), eq(2), eq(0), any());
		}

		@Test
		@DisplayName("7 일 연속이면 7 일째 자리다(나머지 0 은 7)")
		void sevenDayStreak() {
			givenRecordedDaysAgo(1, 2, 3, 4, 5, 6, 7);

			rewardDailyCommandService.seedAttendanceFromRecordStreak(MEMBER_ID, today);

			then(attendanceDayRepository).should()
				.insertIfAbsent(eq(MEMBER_ID), eq(today.minusDays(1)), eq(7), eq(0), any());
		}

		@Test
		@DisplayName("그제까지만 썼으면 끊긴 것이라 자리를 잡지 않는다")
		void brokenStreak() {
			givenRecordedDaysAgo(2, 3, 4);

			rewardDailyCommandService.seedAttendanceFromRecordStreak(MEMBER_ID, today);

			then(attendanceDayRepository).should(never()).insertIfAbsent(anyLong(), any(), anyInt(), anyInt(), any());
		}
	}
}

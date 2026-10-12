package sweetgirl.croissant.reward.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseProgress;
import sweetgirl.croissant.course.enums.CourseProgressStatus;
import sweetgirl.croissant.course.enums.CourseType;
import sweetgirl.croissant.course.repository.CourseProgressRepository;
import sweetgirl.croissant.course.repository.CourseStoreVisitRepository;
import sweetgirl.croissant.course.service.CourseAccessPolicy;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.reward.dto.response.RewardItemResponse;
import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.enums.RewardItemType;
import sweetgirl.croissant.reward.enums.RewardRefType;
import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.repository.CrumbLedgerRepository;
import sweetgirl.croissant.reward.service.GrantResult.Outcome;

@ExtendWith(MockitoExtension.class)
class TourRewardServiceTest {

	private static final Long MEMBER_ID = 7L;
	private static final Long COURSE_ID = 812L;
	private static final Long STORE_ID = 10L;
	/** 월요일. 그 주는 10/12 ~ 10/18 이다. */
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);
	private static final LocalDate WEEK_START = LocalDate.of(2026, 10, 12);

	@Mock
	private CrumbGrantService crumbGrantService;
	@Mock
	private RewardSettings rewardSettings;
	@Mock
	private CrumbLedgerRepository ledgerRepository;
	@Mock
	private CourseProgressRepository courseProgressRepository;
	@Mock
	private CourseStoreVisitRepository courseStoreVisitRepository;
	@Mock
	private CourseAccessPolicy courseAccessPolicy;

	@InjectMocks
	private TourRewardService tourRewardService;

	private Member member;

	@BeforeEach
	void setUp() {
		member = Member.registering("청순한 포카치아", LocalDateTime.of(2026, 9, 1, 12, 0));
		ReflectionTestUtils.setField(member, "id", MEMBER_ID);
		lenient().when(rewardSettings.get(RewardSettingKey.TOUR_VISIT)).thenReturn(3);
		lenient().when(rewardSettings.get(RewardSettingKey.TOUR_VISIT_DAILY_CAP)).thenReturn(8);
		lenient().when(rewardSettings.get(RewardSettingKey.TOUR_COMPLETE)).thenReturn(30);
		lenient().when(rewardSettings.get(RewardSettingKey.TOUR_PHOTO_COMPLETE)).thenReturn(30);
	}

	private Course course(CourseType type, LocalDate startDate, LocalDate endDate) {
		Course course = mock(Course.class);
		lenient().when(course.getId()).thenReturn(COURSE_ID);
		lenient().when(course.getType()).thenReturn(type);
		lenient().when(course.getStartDate()).thenReturn(startDate);
		lenient().when(course.getEndDate()).thenReturn(endDate);
		return course;
	}

	private CourseProgress progress(LocalDate firstVisitedOn) {
		CourseProgress progress = mock(CourseProgress.class);
		lenient().when(progress.getFirstVisitedOn()).thenReturn(firstVisitedOn);
		return progress;
	}

	private void givenVisitGrant(GrantResult result) {
		given(crumbGrantService.grantWithDailyCap(eq(7L), eq(CrumbReason.TOUR_VISIT), eq(3),
			eq("tour_visit:812:10"), eq(RewardRefType.STORE), eq(10L), eq(8), eq(TODAY))).willReturn(result);
	}

	@Nested
	@DisplayName("가게 다녀옴")
	class RewardVisit {

		@ParameterizedTest(name = "시작 {0} · 끝 {1} → {2}")
		@CsvSource({
			"2026-10-14, , true", "2026-10-13, , true", "2026-10-12, , false",
			"2026-10-10, 2026-10-13, true", "2026-10-10, 2026-10-12, false", "2026-10-15, 2026-10-16, false"})
		@DisplayName("내 빵투어에 날짜가 있으면 그 날짜 ~ 끝난 다음 날에만 받는다")
		void ownDatedTour(LocalDate startDate, LocalDate endDate, boolean rewarded) {
			Course course = course(CourseType.CUSTOM, startDate, endDate);
			given(courseAccessPolicy.isOwner(course, MEMBER_ID)).willReturn(true);
			if (rewarded) {
				givenVisitGrant(new GrantResult(Outcome.GRANTED, 3, 0, 3));
			}

			List<RewardItemResponse> rewards = tourRewardService.rewardVisit(
				member, course, STORE_ID, progress(TODAY.minusDays(30)), TODAY);

			assertThat(rewards).hasSize(rewarded ? 1 : 0);
		}

		@Test
		@DisplayName("일행인 빵투어도 날짜 안이면 받는다")
		void memberOfTour() {
			Course course = course(CourseType.CUSTOM, TODAY, null);
			given(courseAccessPolicy.isOwner(course, MEMBER_ID)).willReturn(false);
			given(courseAccessPolicy.isActiveMember(course, MEMBER_ID)).willReturn(true);
			givenVisitGrant(new GrantResult(Outcome.GRANTED, 3, 0, 3));

			assertThat(tourRewardService.rewardVisit(member, course, STORE_ID, progress(TODAY), TODAY))
				.singleElement().satisfies(reward -> assertThat(reward.reason()).isEqualTo(CrumbReason.TOUR_VISIT));
		}

		@ParameterizedTest(name = "처음 다녀온 지 {0}일 → {1}")
		@CsvSource({"0, true", "1, true", "2, false"})
		@DisplayName("날짜 없는 내 빵투어는 처음 다녀온 날과 그 다음 날까지 받는다")
		void datelessTour(int daysSinceFirstVisit, boolean rewarded) {
			Course course = course(CourseType.CUSTOM, null, null);
			lenient().when(courseAccessPolicy.isOwner(course, MEMBER_ID)).thenReturn(true);
			if (rewarded) {
				givenVisitGrant(new GrantResult(Outcome.GRANTED, 3, 0, 3));
			}

			assertThat(tourRewardService.rewardVisit(
				member, course, STORE_ID, progress(TODAY.minusDays(daysSinceFirstVisit)), TODAY))
				.hasSize(rewarded ? 1 : 0);
		}

		@Test
		@DisplayName("담은(남이 만든) 빵투어는 원본의 날짜가 아니라 내가 처음 다녀온 날로 본다")
		void savedTourUsesFirstVisit() {
			Course forked = course(CourseType.FORKED, TODAY.minusMonths(2), TODAY.minusMonths(2));
			givenVisitGrant(new GrantResult(Outcome.GRANTED, 3, 0, 3));

			assertThat(tourRewardService.rewardVisit(member, forked, STORE_ID, progress(TODAY), TODAY)).hasSize(1);
		}

		@Test
		@DisplayName("같은 가게를 오늘 이미 받았으면(다른 빵투어에서) 또 주지 않는다")
		void sameStoreOncePerDay() {
			Course course = course(CourseType.CUSTOM, null, null);
			given(ledgerRepository.existsByMemberIdAndReasonAndRefTypeAndRefIdAndEarnedOn(
				MEMBER_ID, CrumbReason.TOUR_VISIT, RewardRefType.STORE, STORE_ID, TODAY)).willReturn(true);

			assertThat(tourRewardService.rewardVisit(member, course, STORE_ID, progress(TODAY), TODAY)).isEmpty();
			then(crumbGrantService).should(never()).grantWithDailyCap(
				anyLong(), any(), anyInt(), any(), any(), any(), anyInt(), any());
		}

		@Test
		@DisplayName("상한을 세기 전에 지갑을 잠근다 — 같은 가게 확인과 지급이 한 줄에 선다")
		void locksBeforeChecking() {
			Course course = course(CourseType.CUSTOM, null, null);
			givenVisitGrant(new GrantResult(Outcome.GRANTED, 3, 0, 3));

			tourRewardService.rewardVisit(member, course, STORE_ID, progress(TODAY), TODAY);

			var inOrder = org.mockito.Mockito.inOrder(crumbGrantService, ledgerRepository);
			inOrder.verify(crumbGrantService).serialize(MEMBER_ID);
			inOrder.verify(ledgerRepository).existsByMemberIdAndReasonAndRefTypeAndRefIdAndEarnedOn(
				MEMBER_ID, CrumbReason.TOUR_VISIT, RewardRefType.STORE, STORE_ID, TODAY);
		}

		@Test
		@DisplayName("하루 9 번째 가게는 capped 다")
		void ninthStoreCapped() {
			Course course = course(CourseType.CUSTOM, null, null);
			givenVisitGrant(new GrantResult(Outcome.CAPPED, 0, 24, 24));

			assertThat(tourRewardService.rewardVisit(member, course, STORE_ID, progress(TODAY), TODAY))
				.singleElement().satisfies(reward -> assertThat(reward.capped()).isTrue());
		}

		@Test
		@DisplayName("같은 빵투어의 같은 가게를 풀었다 다시 표시해도 또 주지 않는다")
		void recheckedStore() {
			Course course = course(CourseType.CUSTOM, null, null);
			givenVisitGrant(new GrantResult(Outcome.ALREADY_GRANTED, 0, 3, 3));

			assertThat(tourRewardService.rewardVisit(member, course, STORE_ID, progress(TODAY), TODAY)).isEmpty();
		}

		@Test
		@DisplayName("게스트는 보상이 없다")
		void guest() {
			member.markAsGuest();

			assertThat(tourRewardService.rewardVisit(
				member, course(CourseType.CUSTOM, null, null), STORE_ID, progress(TODAY), TODAY)).isEmpty();
			then(crumbGrantService).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("완주")
	class RewardCompletions {

		@Test
		@DisplayName("이번 주 첫 완주는 단계 칸과 +30 을 순서대로 싣는다")
		void firstCompletionOfTheWeek() {
			given(crumbGrantService.grantOn(7L, CrumbReason.TOUR_COMPLETE, 30, "tour_complete:812",
				RewardRefType.COURSE, 812L, TODAY)).willReturn(new GrantResult(Outcome.GRANTED, 30, 10, 40));

			List<RewardItemResponse> rewards = tourRewardService.rewardCompletions(member, List.of(COURSE_ID), TODAY);

			assertThat(rewards).extracting(RewardItemResponse::type)
				.containsExactly(RewardItemType.TOUR_STEP, RewardItemType.CRUMBS);
			assertThat(rewards.get(0).courseId()).isEqualTo(COURSE_ID);
			assertThat(rewards.get(0).step()).isEqualTo("COMPLETE");
			assertThat(rewards.get(1).amount()).isEqualTo(30);
		}

		@Test
		@DisplayName("같은 주의 두 번째 완주는 단계는 채워지되 부스러기는 capped 다 — 월~일로 센다")
		void secondCompletionOfTheWeek() {
			given(ledgerRepository.existsByMemberIdAndReasonAndEarnedOnBetween(
				MEMBER_ID, CrumbReason.TOUR_COMPLETE, WEEK_START, WEEK_START.plusDays(6))).willReturn(true);
			given(crumbGrantService.balanceOf(MEMBER_ID)).willReturn(40);

			List<RewardItemResponse> rewards = tourRewardService.rewardCompletions(member, List.of(COURSE_ID), TODAY);

			assertThat(rewards.get(0).step()).isEqualTo("COMPLETE");
			assertThat(rewards.get(1).capped()).isTrue();
			assertThat(rewards.get(1).amount()).isZero();
			assertThat(rewards.get(1).to()).isEqualTo(40);
			then(crumbGrantService).should(never()).grantOn(anyLong(), any(), anyInt(), any(), any(), any(), any());
		}

		@Test
		@DisplayName("방금 완주한 것이 없으면 아무것도 하지 않는다")
		void nothingFinished() {
			assertThat(tourRewardService.rewardCompletions(member, List.of(), TODAY)).isEmpty();
			then(crumbGrantService).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("사진 완주")
	class RewardPhotoCompletions {

		private CourseProgress finished() {
			CourseProgress progress = mock(CourseProgress.class);
			Course course = mock(Course.class);
			given(course.getId()).willReturn(COURSE_ID);
			given(progress.getCourse()).willReturn(course);
			given(
				courseProgressRepository.findAllByMemberIdAndStatusAndFirstVisitedOnIsNotNullAndPhotoCompletedAtIsNull(
					MEMBER_ID, CourseProgressStatus.FINISHED))
				.willReturn(List.of(progress));
			return progress;
		}

		@Test
		@DisplayName("모든 가게에 사진 붙은 기록이 달리면 +30 이고, 판정이 끝난 것으로 남긴다")
		void allStoresHavePhotos() {
			CourseProgress progress = finished();
			given(courseStoreVisitRepository.countStoresWithoutPhotoVisit(COURSE_ID, MEMBER_ID)).willReturn(0L);
			given(crumbGrantService.grantOn(7L, CrumbReason.TOUR_PHOTO_COMPLETE, 30, "tour_photo:812",
				RewardRefType.COURSE, 812L, TODAY)).willReturn(new GrantResult(Outcome.GRANTED, 30, 40, 70));

			List<RewardItemResponse> rewards = tourRewardService.rewardPhotoCompletions(member, TODAY);

			assertThat(rewards.get(0).step()).isEqualTo("PHOTO_COMPLETE");
			assertThat(rewards.get(1).reason()).isEqualTo(CrumbReason.TOUR_PHOTO_COMPLETE);
			then(progress).should().markPhotoCompleted(any());
		}

		@Test
		@DisplayName("사진 없는 가게가 하나라도 남았으면 아직 아니다 — 다음에 다시 본다")
		void someStoreWithoutPhoto() {
			CourseProgress progress = finished();
			given(courseStoreVisitRepository.countStoresWithoutPhotoVisit(COURSE_ID, MEMBER_ID)).willReturn(1L);

			assertThat(tourRewardService.rewardPhotoCompletions(member, TODAY)).isEmpty();
			then(progress).should(never()).markPhotoCompleted(any());
		}

		@Test
		@DisplayName("이번 주에 사진 완주를 이미 받았으면 capped 이고, 그래도 판정은 끝난다 — 다음 주에 뒤늦게 주지 않는다")
		void cappedButStillMarked() {
			CourseProgress progress = finished();
			given(courseStoreVisitRepository.countStoresWithoutPhotoVisit(COURSE_ID, MEMBER_ID)).willReturn(0L);
			given(ledgerRepository.existsByMemberIdAndReasonAndEarnedOnBetween(
				MEMBER_ID, CrumbReason.TOUR_PHOTO_COMPLETE, WEEK_START, WEEK_START.plusDays(6))).willReturn(true);

			List<RewardItemResponse> rewards = tourRewardService.rewardPhotoCompletions(member, TODAY);

			assertThat(rewards.get(1).capped()).isTrue();
			then(progress).should().markPhotoCompleted(any());
		}

		@Test
		@DisplayName("완주 보상을 받은 주에도 사진 완주는 따로 센다")
		void countedSeparatelyFromCompletion() {
			finished();
			given(courseStoreVisitRepository.countStoresWithoutPhotoVisit(COURSE_ID, MEMBER_ID)).willReturn(0L);
			given(crumbGrantService.grantOn(7L, CrumbReason.TOUR_PHOTO_COMPLETE, 30, "tour_photo:812",
				RewardRefType.COURSE, 812L, TODAY)).willReturn(new GrantResult(Outcome.GRANTED, 30, 40, 70));

			tourRewardService.rewardPhotoCompletions(member, TODAY);

			then(ledgerRepository).should(never()).existsByMemberIdAndReasonAndEarnedOnBetween(
				eq(MEMBER_ID), eq(CrumbReason.TOUR_COMPLETE), any(), any());
		}
	}
}

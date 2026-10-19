package sweetgirl.croissant.reward.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.course.dto.projection.CourseStoreSummaryProjection;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseImageFile;
import sweetgirl.croissant.course.entity.CourseStoreVisit;
import sweetgirl.croissant.course.entity.CourseStoreVisitId;
import sweetgirl.croissant.course.repository.CourseImageFileRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.course.repository.CourseStoreVisitRepository;
import sweetgirl.croissant.reward.dto.response.HomeCardResponse;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse.Letter;
import sweetgirl.croissant.world.service.WorldQueryService;

@ExtendWith(MockitoExtension.class)
class HomeCardServiceTest {

	private static final Long MEMBER_ID = 7L;
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);
	private static final Letter NO_LETTER = new Letter(0, null);
	private static final Letter ONE_LETTER = new Letter(1, 31L);

	@Mock
	private WorldQueryService worldQueryService;
	@Mock
	private CourseRepository courseRepository;
	@Mock
	private CourseStoreRepository courseStoreRepository;
	@Mock
	private CourseStoreVisitRepository courseStoreVisitRepository;
	@Mock
	private CourseImageFileRepository courseImageFileRepository;

	@InjectMocks
	private HomeCardService homeCardService;

	private Course tour(Long id, String title, LocalDate startDate) {
		Course tour = mock(Course.class);
		lenient().when(tour.getId()).thenReturn(id);
		lenient().when(tour.getTitle()).thenReturn(title);
		lenient().when(tour.getStartDate()).thenReturn(startDate);
		return tour;
	}

	private void givenTours(Course... tours) {
		given(courseRepository.findMyDatedCourses(MEMBER_ID, TODAY, TODAY.plusDays(7))).willReturn(List.of(tours));
	}

	private void givenTourToday() {
		Course today = tour(812L, "성수 빵투어", TODAY.minusDays(1));
		givenTours(today, tour(900L, "망원 빵투어", TODAY.plusDays(2)));
		// 헬퍼가 다른 mock 을 스텁하므로 when(...) 안에서 부르면 UnfinishedStubbing 이 난다 — 먼저 만든다.
		List<CourseStoreSummaryProjection> stops = List.of(
			stop(1L, "첫 가게"), stop(2L, "둘째 가게"), stop(3L, "셋째 가게"), stop(4L, "넷째 가게"), stop(5L, "다섯째 가게"));
		List<CourseStoreVisit> visits = List.of(visit(1L), visit(2L));
		lenient().when(courseStoreRepository.findSummariesByCourseIdIn(List.of(812L))).thenReturn(stops);
		lenient().when(courseStoreVisitRepository.findAllByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(812L, MEMBER_ID))
			.thenReturn(visits);
		CourseImageFile cover = mock(CourseImageFile.class);
		lenient().when(cover.getAwsKey()).thenReturn("courses/812/cover.jpg");
		lenient().when(courseImageFileRepository.findByCourseId(812L)).thenReturn(Optional.of(cover));
	}

	private CourseStoreSummaryProjection stop(Long storeId, String storeName) {
		CourseStoreSummaryProjection stop = mock(CourseStoreSummaryProjection.class);
		lenient().when(stop.getStoreId()).thenReturn(storeId);
		lenient().when(stop.getStoreName()).thenReturn(storeName);
		return stop;
	}

	private CourseStoreVisit visit(Long storeId) {
		CourseStoreVisit visit = mock(CourseStoreVisit.class);
		CourseStoreVisitId id = mock(CourseStoreVisitId.class);
		lenient().when(id.getStoreId()).thenReturn(storeId);
		lenient().when(visit.getId()).thenReturn(id);
		return visit;
	}

	@Test
	@DisplayName("룰렛이 밀려 있으면 다른 것이 다 겹쳐도 룰렛이 먼저다")
	void rouletteFirst() {
		given(worldQueryService.pendingRoulettePlanets(MEMBER_ID)).willReturn(List.of("mars", "jupiter"));

		Optional<HomeCardResponse> card = homeCardService.cardFor(MEMBER_ID, ONE_LETTER, TODAY);

		assertThat(card).contains(HomeCardResponse.roulette("mars"));
		then(courseRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("오늘이 빵투어 날이면 편지·밥보다 먼저이고, 다녀온 가게 수·다음 가게·표지를 싣는다")
	void tourTodayBeforeLetterAndFeed() {
		givenTourToday();

		Optional<HomeCardResponse> card = homeCardService.cardFor(MEMBER_ID, ONE_LETTER, TODAY);

		assertThat(card).contains(
			HomeCardResponse.tourToday(812L, "성수 빵투어", 2, 5, "셋째 가게", "courses/812/cover.jpg"));
	}

	@Test
	@DisplayName("안 연 편지가 밥보다 먼저다")
	void letterBeforeFeed() {
		givenTours(tour(900L, "망원 빵투어", TODAY.plusDays(2)));

		assertThat(homeCardService.cardFor(MEMBER_ID, ONE_LETTER, TODAY))
			.contains(HomeCardResponse.letter(31L));
	}

	@Test
	@DisplayName("밥은 카드로 고르지 않는다 — 다가오는 빵투어가 밥에 가려지지 않는다")
	void feedNeverChosen() {
		givenTours(tour(900L, "망원 빵투어", TODAY.plusDays(2)));
		given(courseStoreRepository.countByCourseIdAndDeletedAtIsNull(900L)).willReturn(4L);

		assertThat(homeCardService.cardFor(MEMBER_ID, NO_LETTER, TODAY))
			.contains(HomeCardResponse.tourSoon(900L, "망원 빵투어", 2, 4, null));
	}

	@Test
	@DisplayName("남은 것이 다가오는 빵투어뿐이면 가장 이른 것의 D-day 와 가게 수를 싣는다")
	void tourSoon() {
		givenTours(tour(900L, "망원 빵투어", TODAY.plusDays(2)), tour(901L, "연남 빵투어", TODAY.plusDays(6)));
		given(courseStoreRepository.countByCourseIdAndDeletedAtIsNull(900L)).willReturn(4L);

		assertThat(homeCardService.cardFor(MEMBER_ID, NO_LETTER, TODAY))
			.contains(HomeCardResponse.tourSoon(900L, "망원 빵투어", 2, 4, null));
	}

	@Test
	@DisplayName("할 일이 하나도 없으면 카드가 없다")
	void none() {
		givenTours();

		assertThat(homeCardService.cardFor(MEMBER_ID, NO_LETTER, TODAY)).isEmpty();
	}
}

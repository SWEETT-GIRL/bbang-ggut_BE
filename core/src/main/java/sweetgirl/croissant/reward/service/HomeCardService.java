package sweetgirl.croissant.reward.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.course.dto.projection.CourseStoreSummaryProjection;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseImageFile;
import sweetgirl.croissant.course.repository.CourseImageFileRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.course.repository.CourseStoreVisitRepository;
import sweetgirl.croissant.reward.dto.response.HomeCardResponse;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse.Letter;
import sweetgirl.croissant.world.service.WorldQueryService;

/**
 * 홈의 할 일 카드 — 지금 할 만한 것 가운데 한 장만 고른다.
 *
 * <p>순서는 서버가 정한다: 룰렛 → 오늘 빵투어 → 편지 → 다가오는 빵투어. 아무것도 없으면 카드가 없다.
 * 밥은 카드로 고르지 않는다 — 앱이 밥을 도크로만 보여 주고, 밥 카드가 뽑히면 그 아래 다가오는 빵투어가 가려진다.
 * "오늘 먹은 빵 기록" 카드는 없다 — 홈의 기록 버튼이 맡는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HomeCardService {

	/** 이 날수 안에 시작하는 빵투어를 "곧"으로 본다. */
	static final int SOON_DAYS = 7;

	private final WorldQueryService worldQueryService;
	private final CourseRepository courseRepository;
	private final CourseStoreRepository courseStoreRepository;
	private final CourseStoreVisitRepository courseStoreVisitRepository;
	private final CourseImageFileRepository courseImageFileRepository;

	/** @param letter 요약이 이미 읽은 편지 상태 */
	public Optional<HomeCardResponse> cardFor(Long memberId, Letter letter, LocalDate today) {
		List<String> pendingRoulettePlanets = worldQueryService.pendingRoulettePlanets(memberId);
		if (!pendingRoulettePlanets.isEmpty()) {
			return Optional.of(HomeCardResponse.roulette(pendingRoulettePlanets.get(0)));
		}

		List<Course> tours = courseRepository.findMyDatedCourses(memberId, today, today.plusDays(SOON_DAYS));
		Optional<Course> tourToday = tours.stream().filter(tour -> !tour.getStartDate().isAfter(today)).findFirst();
		if (tourToday.isPresent()) {
			return Optional.of(tourTodayCard(tourToday.get(), memberId));
		}
		if (letter.waitingCount() > 0) {
			return Optional.of(HomeCardResponse.letter(letter.latestId()));
		}
		// 오늘 걸린 빵투어가 없으니 남은 것은 모두 내일 이후에 시작한다 — 가장 이른 것 하나
		return tours.stream().findFirst().map(tour -> HomeCardResponse.tourSoon(
			tour.getId(), tour.getTitle(), (int)ChronoUnit.DAYS.between(today, tour.getStartDate()),
			(int)courseStoreRepository.countByCourseIdAndDeletedAtIsNull(tour.getId()), coverImageKeyOf(tour.getId())));
	}

	/** 오늘 빵투어 — 다녀온 가게 수, 다음에 갈 가게(방문 순서에서 아직 안 간 첫 가게), 표지. */
	private HomeCardResponse tourTodayCard(Course tour, Long memberId) {
		List<CourseStoreSummaryProjection> stops = courseStoreRepository.findSummariesByCourseIdIn(
			List.of(tour.getId()));
		Set<Long> visitedStoreIds = courseStoreVisitRepository
			.findAllByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(tour.getId(), memberId).stream()
			.map(visit -> visit.getId().getStoreId())
			.collect(Collectors.toSet());
		String nextStoreName = stops.stream()
			.filter(stop -> !visitedStoreIds.contains(stop.getStoreId()))
			.map(CourseStoreSummaryProjection::getStoreName)
			.findFirst()
			.orElse(null);
		return HomeCardResponse.tourToday(tour.getId(), tour.getTitle(), visitedStoreIds.size(), stops.size(),
			nextStoreName, coverImageKeyOf(tour.getId()));
	}

	private String coverImageKeyOf(Long courseId) {
		return courseImageFileRepository.findByCourseId(courseId).map(CourseImageFile::getAwsKey).orElse(null);
	}
}

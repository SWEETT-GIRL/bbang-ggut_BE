package sweetgirl.croissant.reward.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.course.repository.CourseStoreVisitRepository;
import sweetgirl.croissant.reward.dto.response.HomeCardResponse;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse.Letter;
import sweetgirl.croissant.world.service.WorldQueryService;

/**
 * 홈의 할 일 카드 — 지금 할 만한 것 가운데 한 장만 고른다.
 *
 * <p>순서는 서버가 정한다: 룰렛 → 오늘 빵투어 → 편지 → 밥 → 다가오는 빵투어. 아무것도 없으면 카드가 없다.
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

	/**
	 * @param letter   요약이 이미 읽은 편지 상태
	 * @param fedToday 오늘 밥 룰렛을 돌렸는가
	 */
	public Optional<HomeCardResponse> cardFor(Long memberId, Letter letter, boolean fedToday, LocalDate today) {
		List<String> pendingRoulettePlanets = worldQueryService.pendingRoulettePlanets(memberId);
		if (!pendingRoulettePlanets.isEmpty()) {
			return Optional.of(HomeCardResponse.roulette(pendingRoulettePlanets.get(0)));
		}

		List<Course> tours = courseRepository.findMyDatedCourses(memberId, today, today.plusDays(SOON_DAYS));
		Optional<Course> tourToday = tours.stream().filter(tour -> !tour.getStartDate().isAfter(today)).findFirst();
		if (tourToday.isPresent()) {
			Course tour = tourToday.get();
			return Optional.of(HomeCardResponse.tourToday(
				tour.getId(), tour.getTitle(),
				courseStoreVisitRepository.findAllByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(tour.getId(), memberId)
					.size(),
				(int)courseStoreRepository.countByCourseIdAndDeletedAtIsNull(tour.getId())));
		}
		if (letter.waitingCount() > 0) {
			return Optional.of(HomeCardResponse.letter(letter.latestId()));
		}
		if (!fedToday) {
			return Optional.of(HomeCardResponse.feed());
		}
		// 오늘 걸린 빵투어가 없으니 남은 것은 모두 내일 이후에 시작한다 — 가장 이른 것 하나
		return tours.stream().findFirst().map(tour -> HomeCardResponse.tourSoon(
			tour.getId(), tour.getTitle(), (int)ChronoUnit.DAYS.between(today, tour.getStartDate())));
	}
}

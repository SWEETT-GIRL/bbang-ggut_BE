package sweetgirl.croissant.recommendation.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.service.CourseAccessPolicy;
import sweetgirl.croissant.personalization.config.PersonalizationProperties;
import sweetgirl.croissant.personalization.entity.UserTasteProfile;
import sweetgirl.croissant.personalization.enums.BbtiType;
import sweetgirl.croissant.personalization.repository.TasteSignalJdbcRepository;
import sweetgirl.croissant.personalization.repository.UserTasteProfileRepository;
import sweetgirl.croissant.recommendation.config.RecommendationProperties;
import sweetgirl.croissant.recommendation.dto.response.RecommendationAreaResponse;
import sweetgirl.croissant.recommendation.dto.response.RecommendationContextResponse;
import sweetgirl.croissant.recommendation.entity.CourseArea;
import sweetgirl.croissant.recommendation.enums.RecommendationAreaReason;
import sweetgirl.croissant.recommendation.enums.RecommendationContextState;
import sweetgirl.croissant.recommendation.repository.AreaChipStats;
import sweetgirl.croissant.recommendation.repository.CourseAreaRepository;
import sweetgirl.croissant.recommendation.repository.RecommendationCandidateJdbcRepository;

/**
 * 빈 코스 카드가 무엇을 보일지 (설계 §1 · §5.2 · #660 임시 규칙).
 *
 * 상태: 검사 타입이 있으면 CHIPS. 없으면 강한 신호(저장 · 담기 · 기록) 가게가 3곳 이상이어야 CHIPS, 아니면 QUIZ.
 * 정확도 링크: CHIPS 인데 검사를 안 했고 빵 단위 신호(기록)가 없을 때.
 * 칩: 코스 지역(시/도)의 활성 상권을 표시 순서대로 3개. 근거는 저장 가게가 상권 안에 있으면 SAVED, 아니면 POPULAR
 * (SESSION · TASTE 는 #661). 상권 시드 전에는 칩이 비어 카드가 뜨지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationContextQueryService {

	private final CourseRepository courseRepository;
	private final CourseAccessPolicy courseAccessPolicy;
	private final CourseAreaRepository courseAreaRepository;
	private final UserTasteProfileRepository userTasteProfileRepository;
	private final TasteSignalJdbcRepository tasteSignalJdbcRepository;
	private final BreadRecordRepository breadRecordRepository;
	private final RecommendationCandidateJdbcRepository candidateRepository;
	private final RecommendationProperties recommendationProperties;
	private final PersonalizationProperties personalizationProperties;

	public RecommendationContextResponse getContext(Long memberId, Long courseId) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		courseAccessPolicy.requireViewable(course, memberId, null);

		Optional<UserTasteProfile> profile = userTasteProfileRepository.findById(memberId);
		String typeLabel = profile.filter(UserTasteProfile::hasType)
			.map(found -> BbtiType.fromCode(found.getTypeCode()).getLabel())
			.orElse(null);
		boolean quizTaken = profile.map(UserTasteProfile::hasTakenQuiz).orElse(false);

		RecommendationContextState state = typeLabel != null
			|| tasteSignalJdbcRepository.countStrongSignalStores(memberId) >= personalizationProperties
				.getStrongSignalStoresForChips()
					? RecommendationContextState.CHIPS
					: RecommendationContextState.QUIZ;
		boolean showAccuracyLink = state == RecommendationContextState.CHIPS && !quizTaken
			&& breadRecordRepository.countByMemberIdAndDeletedAtIsNull(memberId) == 0;

		return new RecommendationContextResponse(state, showAccuracyLink, areasOf(course, memberId), typeLabel);
	}

	/**
	 * 도장 칸마다 칩 하나(#736): 같은 passport_spot 의 동네 행들을 묶고 첫 행(display_order 최소)이 칩 id·순서가 된다.
	 * 칩 이름은 도장 칸 라벨, 가게 수는 동네들의 합, 저장 가게는 동네 중 하나라도 있으면 SAVED. 동네가 하나뿐인 칸은 그대로다.
	 */
	private List<RecommendationAreaResponse> areasOf(Course course, Long memberId) {
		if (course.getRegion() == null) {
			return List.of();
		}
		Map<String, List<CourseArea>> spots = new LinkedHashMap<>();
		for (CourseArea area : courseAreaRepository
			.findAllBySidoAndIsActiveTrueOrderByDisplayOrderAscNameAsc(course.getRegion())) {
			spots.computeIfAbsent(area.getPassportSpot(), spot -> new ArrayList<>()).add(area);
		}
		List<List<CourseArea>> shown = spots.values().stream()
			.limit(recommendationProperties.getArea().getMaxChips())
			.toList();
		// 칩마다 두 번 묻지 않고 한 문장으로 — 후보와 같은 소속 규칙로 센다
		Map<Long, AreaChipStats> stats = candidateRepository.findAreaChipStats(memberId,
			shown.stream().flatMap(List::stream).toList());
		return shown.stream()
			.map(neighborhoods -> {
				CourseArea primary = neighborhoods.get(0);
				int storeCount = 0;
				boolean hasSavedStore = false;
				for (CourseArea neighborhood : neighborhoods) {
					AreaChipStats chip = stats.getOrDefault(neighborhood.getId(),
						new AreaChipStats(neighborhood.getId(), 0, false));
					storeCount += chip.storeCount();
					hasSavedStore |= chip.hasSavedStore();
				}
				return new RecommendationAreaResponse(primary.getId(), primary.getPassportSpot(),
					hasSavedStore ? RecommendationAreaReason.SAVED : RecommendationAreaReason.POPULAR, storeCount);
			})
			.toList();
	}
}

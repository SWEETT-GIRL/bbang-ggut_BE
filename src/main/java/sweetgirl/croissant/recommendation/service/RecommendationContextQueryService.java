package sweetgirl.croissant.recommendation.service;

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

	private List<RecommendationAreaResponse> areasOf(Course course, Long memberId) {
		if (course.getRegion() == null) {
			return List.of();
		}
		List<CourseArea> areas = courseAreaRepository
			.findAllBySidoAndIsActiveTrueOrderByDisplayOrderAscNameAsc(course.getRegion())
			.stream()
			.limit(recommendationProperties.getArea().getMaxChips())
			.toList();
		// 칩마다 두 번 묻지 않고 한 문장으로 — 후보와 같은 소속 규칙로 센다
		Map<Long, AreaChipStats> stats = candidateRepository.findAreaChipStats(memberId, areas);
		return areas.stream()
			.map(area -> {
				AreaChipStats chip = stats.getOrDefault(area.getId(), new AreaChipStats(area.getId(), 0, false));
				return new RecommendationAreaResponse(area.getId(), area.getName(),
					chip.hasSavedStore() ? RecommendationAreaReason.SAVED : RecommendationAreaReason.POPULAR,
					chip.storeCount());
			})
			.toList();
	}
}

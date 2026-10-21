package sweetgirl.croissant.recommendation.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
import sweetgirl.croissant.recommendation.repository.PolygonAreaBindingRepository;
import sweetgirl.croissant.recommendation.repository.RecommendationCandidateJdbcRepository;
import sweetgirl.croissant.recommendation.repository.TasteQuery;

/**
 * 빈 코스 카드가 무엇을 보일지 (설계 §1 · §5.2 · #660 임시 규칙).
 *
 * 상태: 검사 타입이 있으면 CHIPS. 없으면 강한 신호(저장 · 담기 · 기록) 가게가 3곳 이상이어야 CHIPS, 아니면 QUIZ.
 * 정확도 링크: CHIPS 인데 검사를 안 했고 빵 단위 신호(기록)가 없을 때.
 * 칩: 코스 지역(시/도)의 도장 칸을 우선순위 버킷으로 3개 — 이 세션에서 본 가게가 있는 칸(SESSION) → 저장 가게가 있는 칸(SAVED)
 * → 취향 후보가 충분한 칸(TASTE) → 나머지(POPULAR), 버킷 안은 신호 크기 → 도장판 순서(#661 §5.2, areasOf 참고).
 * 시군구 칸(포항시)은 원 밖이라도 같은 시군구에서 본·저장한 가게를 신호로 친다 — 원은 그 도시 빵집의 30% 안팎만 덮는다.
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
	private final PolygonAreaBindingRepository polygonAreaBindingRepository;

	public RecommendationContextResponse getContext(Long memberId, Long courseId, String sessionId) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		courseAccessPolicy.requireViewable(course, memberId, null);

		Optional<UserTasteProfile> profile = userTasteProfileRepository.findById(memberId);
		boolean hasType = profile.map(UserTasteProfile::hasType).orElse(false);
		boolean quizTaken = profile.map(UserTasteProfile::hasTakenQuiz).orElse(false);

		RecommendationContextState state = hasType
			|| tasteSignalJdbcRepository.countStrongSignalStores(memberId) >= personalizationProperties
				.getStrongSignalStoresForChips()
					? RecommendationContextState.CHIPS
					: RecommendationContextState.QUIZ;
		boolean showAccuracyLink = state == RecommendationContextState.CHIPS && !quizTaken
			&& breadRecordRepository.countByMemberIdAndDeletedAtIsNull(memberId) == 0;

		return new RecommendationContextResponse(state, showAccuracyLink,
			areasOf(course, memberId, sessionId, TasteQuery.of(profile.orElse(null))));
	}

	/**
	 * 도장 칸마다 칩 하나(#736): 같은 passport_spot 의 동네 행들을 묶고 첫 행(display_order 최소)이 칩 id 가 된다.
	 * 칩 이름은 도장 칸 라벨, 가게 수는 동네들의 합. 동네가 하나뿐인 칸은 그대로다.
	 *
	 * 칩 순서(#661 §5.2)는 버킷 우선순위다 — 이 세션에서 상세를 본 가게가 있는 칸(SESSION) → 내 저장 가게가 있는 칸(SAVED) →
	 * 취향 후보가 충분한 칸(TASTE, 축이 있을 때만) → 나머지(POPULAR). 버킷 안에서는 신호 크기 순, 같으면 도장판 순서.
	 * reason 은 그 칩이 속한 버킷이다. 세션은 sessionId 가 있으면 같은 세션, 없으면 최근 창(설정)이다.
	 *
	 * <p><b>그 시·도의 도장 칸을 전부 준다 — 자르지 않는다.</b> 예전엔 앞 3개만 줬는데, 서울은 도장 칸이 6개라 신호가 없는
	 * 칸 절반이 늘 안 보였다(성수·연남·망원만 뜨고 한남·공덕·서교는 못 고름). 여권에 있는 지역인데 고를 수가 없다.
	 *
	 * <p>자르는 것이 비용을 줄이지도 않는다 — 위 {@code findAreaChipStats} 가 이미 그 시·도의 모든 칸을 세고 오므로,
	 * 자르기는 계산해 둔 것을 버리는 일이다. 집합도 작다: 도장 칸은 시·도당 최대 6개, 전국 48개다(시드 기준).
	 * 몇 개를 보여줄지는 화면이 정한다 — 서버는 고를 수 있는 것을 다 주고 순서로 말한다.
	 */
	private List<RecommendationAreaResponse> areasOf(Course course, Long memberId, String sessionId,
			TasteQuery taste) {
		if (course.getRegion() == null) {
			return List.of();
		}
		Map<String, List<CourseArea>> spots = new LinkedHashMap<>();
		for (CourseArea area : courseAreaRepository
			.findAllBySidoAndIsActiveTrueOrderByDisplayOrderAscNameAsc(course.getRegion())) {
			spots.computeIfAbsent(area.getPassportSpot(), spot -> new ArrayList<>()).add(area);
		}
		// 칩마다 두 번 묻지 않고 한 문장으로 — 후보와 같은 소속 규칙로 센다
		Map<Long, AreaChipStats> stats = chipStats(memberId, sessionId, taste, spots);
		int minTasteCandidates = recommendationProperties.getScore().getMinTasteCandidates();
		return spots.values().stream()
			.map(neighborhoods -> ChipSignal.of(neighborhoods, stats, taste.hasAxis(), minTasteCandidates))
			.sorted(ChipSignal.ORDER)
			.map(chip -> new RecommendationAreaResponse(chip.primary().getId(), chip.primary().getPassportSpot(),
				chip.reason(), chip.storeCount()))
			.toList();
	}

	private Map<Long, AreaChipStats> chipStats(Long memberId, String sessionId, TasteQuery taste,
			Map<String, List<CourseArea>> spots) {
		if (!recommendationProperties.getPolygon().isEnabled()) {
			return candidateRepository.findAreaChipStats(memberId, sessionId, taste,
				spots.values().stream().flatMap(List::stream).toList());
		}
		Map<Long, AreaChipStats> stats = new HashMap<>();
		for (List<CourseArea> neighborhoods : spots.values()) {
			CourseArea primary = neighborhoods.get(0);
			Optional<Long> binding = polygonAreaBindingRepository.findReadyBinding(
				recommendationProperties.getPolygon().getVersionKey(), primary.getId());
			if (binding.isPresent()) {
				stats.put(primary.getId(), candidateRepository.findPolygonChipStats(memberId, sessionId, taste,
					primary.getId(), binding.get()));
			} else {
				stats.putAll(candidateRepository.findAreaChipStats(memberId, sessionId, taste, neighborhoods));
			}
		}
		return stats;
	}

	/** 도장 칸 하나의 신호 합과 버킷. reason 이 곧 버킷이라 정렬 키다. */
	record ChipSignal(CourseArea primary, RecommendationAreaReason reason, int storeCount, int sessionViewCount,
			int savedStoreCount, int tasteCandidateCount) {

		static final Comparator<ChipSignal> ORDER = Comparator
			.comparingInt((ChipSignal chip) -> chip.reason().getPriority())
			.thenComparingInt((ChipSignal chip) -> -chip.signalStrength())
			.thenComparingInt(chip -> chip.primary().getDisplayOrder())
			.thenComparing(chip -> chip.primary().getName());

		static ChipSignal of(List<CourseArea> neighborhoods, Map<Long, AreaChipStats> stats, boolean hasAxis,
				int minTasteCandidates) {
			int storeCount = 0;
			int sessionViewCount = 0;
			int savedStoreCount = 0;
			int tasteCandidateCount = 0;
			int regionSessionViewCount = 0;
			int regionSavedStoreCount = 0;
			for (CourseArea neighborhood : neighborhoods) {
				AreaChipStats chip = stats.getOrDefault(neighborhood.getId(),
					new AreaChipStats(neighborhood.getId(), 0, false));
				storeCount += chip.storeCount();
				sessionViewCount += chip.sessionViewCount();
				savedStoreCount += chip.savedStoreCount();
				tasteCandidateCount += chip.tasteCandidateCount();
				regionSessionViewCount = Math.max(regionSessionViewCount, chip.regionSessionViewCount());
				regionSavedStoreCount = Math.max(regionSavedStoreCount, chip.regionSavedStoreCount());
			}
			// 시군구 칸은 원 밖에서 본·저장한 같은 시군구 가게도 신호다(포항 장량동 가게를 봤으면 포항시 칩). 시군구 집합이
			// 원 집합을 포함하므로 합이 아니라 큰 값 — 애월 원 안 가게가 애월 행과 제주시 시군구에 두 번 세지지 않게.
			sessionViewCount = Math.max(sessionViewCount, regionSessionViewCount);
			savedStoreCount = Math.max(savedStoreCount, regionSavedStoreCount);
			RecommendationAreaReason reason;
			if (sessionViewCount > 0) {
				reason = RecommendationAreaReason.SESSION;
			} else if (savedStoreCount > 0) {
				reason = RecommendationAreaReason.SAVED;
			} else if (hasAxis && tasteCandidateCount >= minTasteCandidates) {
				reason = RecommendationAreaReason.TASTE;
			} else {
				reason = RecommendationAreaReason.POPULAR;
			}
			return new ChipSignal(neighborhoods.get(0), reason, storeCount, sessionViewCount, savedStoreCount,
				tasteCandidateCount);
		}

		/** 버킷 안 순서 — 그 버킷을 만든 신호의 크기. POPULAR 는 신호가 없어 도장판 순서만 남는다. */
		int signalStrength() {
			return switch (reason) {
				case SESSION -> sessionViewCount;
				case SAVED -> savedStoreCount;
				case TASTE -> tasteCandidateCount;
				case POPULAR -> 0;
			};
		}
	}
}

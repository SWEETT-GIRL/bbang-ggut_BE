package sweetgirl.croissant.recommendation.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.common.util.GeoDistance;
import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseStore;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.course.service.CourseAccessPolicy;
import sweetgirl.croissant.personalization.entity.UserTasteProfile;
import sweetgirl.croissant.personalization.enums.BbtiType;
import sweetgirl.croissant.personalization.repository.UserTasteProfileRepository;
import sweetgirl.croissant.recommendation.code.RecommendationErrorCode;
import sweetgirl.croissant.recommendation.code.RecommendationMessage;
import sweetgirl.croissant.recommendation.config.RecommendationProperties;
import sweetgirl.croissant.recommendation.dto.request.RecommendationRequest;
import sweetgirl.croissant.recommendation.dto.response.AnchorRecommendationResponse;
import sweetgirl.croissant.recommendation.dto.response.AnchorRecommendationResult;
import sweetgirl.croissant.recommendation.dto.response.AnchorRecommendedStoreResponse;
import sweetgirl.croissant.recommendation.dto.response.AnchorStoreSummary;
import sweetgirl.croissant.recommendation.dto.response.RecommendationAreaSummary;
import sweetgirl.croissant.recommendation.dto.response.RecommendationResponse;
import sweetgirl.croissant.recommendation.dto.response.RecommendationResult;
import sweetgirl.croissant.recommendation.dto.response.RecommendedStoreResponse;
import sweetgirl.croissant.recommendation.entity.CourseArea;
import sweetgirl.croissant.recommendation.entity.Recommendation;
import sweetgirl.croissant.recommendation.enums.AnchorReasonTag;
import sweetgirl.croissant.recommendation.exception.RecommendationException;
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;
import sweetgirl.croissant.recommendation.repository.CourseAreaRepository;
import sweetgirl.croissant.recommendation.repository.RecommendationCandidateJdbcRepository;
import sweetgirl.croissant.recommendation.repository.RecommendationRepository;
import sweetgirl.croissant.store.entity.Store;

/**
 * 상권 추천(진입점 1)과 앵커 추천(진입점 2) — #660 의 룰만 도는 임시 구현. LLM 은 #662.
 *
 * 둘 다 재사용 키(회원·코스·상권 또는 앵커·일차·프로필 버전)가 맞는 최신 행이 있으면 저장된 result 를 돌려준다 —
 * 단, 그 사이 코스에 담긴 가게가 결과에 들어 있으면 상권은 다시 만들고 앵커는 그 가게를 뺀다.
 * 상권: 반경 안 ACTIVE 가게를 인기순으로 15곳 뽑아 앞 N 곳을 최근접 순으로 잇는다. 이유는 템플릿, tasteFit 은 축이 없어 0.
 * 앵커: 앵커 좌표 1→2→3km 링 순 30곳을 행 하나에 저장하고 page 로 9곳씩 자른다. 이유 문장 없이 배지만(09-13).
 * 여정 값(entryScreen · sessionEvents)은 받기만 하고 어디에도 쓰지 않는다 — 세션 의도는 #661.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CourseRecommendationCommandService {

	/** 축이 없으니 취향 적합도도 없다. #661 의 점수 계산기가 생기면 그쪽이 주인이 되고 이 값은 사라진다. */
	static final double NO_TASTE_FIT = 0.0;

	private final CourseRepository courseRepository;
	private final CourseStoreRepository courseStoreRepository;
	private final CourseAccessPolicy courseAccessPolicy;
	private final CourseAreaRepository courseAreaRepository;
	private final UserTasteProfileRepository userTasteProfileRepository;
	private final RecommendationRepository recommendationRepository;
	private final RecommendationCandidateJdbcRepository candidateRepository;
	private final RecommendationProperties recommendationProperties;
	private final ObjectMapper objectMapper;

	public RecommendationResponse recommend(Long memberId, Long courseId, RecommendationRequest request) {
		RecommendationProperties.Area areaRules = recommendationProperties.getArea();
		int storeCount = request.storeCount() != null ? request.storeCount() : areaRules.getDefaultStoreCount();
		if (storeCount < areaRules.getMinStoreCount() || storeCount > areaRules.getMaxStoreCount()) {
			throw new RecommendationException(RecommendationErrorCode.STORE_COUNT_OUT_OF_RANGE);
		}
		Course course = findCourse(courseId);
		courseAccessPolicy.requireEditable(course, memberId);
		CourseArea area = courseAreaRepository.findByIdAndIsActiveTrue(request.areaId())
			.filter(found -> found.getSido().equals(course.getRegion()))
			.orElseThrow(() -> new RecommendationException(RecommendationErrorCode.COURSE_AREA_NOT_FOUND));

		List<CourseStore> activeStores = courseStoreRepository
			.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId);
		short day = (short)(request.day() != null ? request.day() : firstEmptyDay(course, activeStores));
		UserTasteProfile profile = userTasteProfileRepository.findById(memberId).orElse(null);
		int profileVersion = profile != null ? profile.getVersion() : Recommendation.NO_PROFILE_VERSION;

		Set<Long> courseStoreIds = storeIdsOf(activeStores);
		Optional<Recommendation> reusable = recommendationRepository
			.findLatestAreaForReuse(memberId, courseId, area.getId(), day, profileVersion);
		if (reusable.isPresent()) {
			RecommendationResult stored = read(reusable.get(), RecommendationResult.class);
			if (isStillValid(stored, courseStoreIds, storeCount)) {
				return RecommendationResponse.of(reusable.get().getId(), stored);
			}
		}
		return buildAreaRecommendation(memberId, course, area, day, profile, profileVersion, courseStoreIds,
			storeCount);
	}

	/**
	 * 저장된 결과를 다시 써도 되는지. 그 사이 코스가 바뀌어 담긴 가게가 결과에 들어 있거나(담으면 중복 오류가 난다),
	 * 요청한 가게 수가 다르면 다시 만든다. 새 행이 최신이 되어 다음부터는 그것이 재사용된다.
	 */
	private static boolean isStillValid(RecommendationResult stored, Set<Long> courseStoreIds, int storeCount) {
		return stored.stores().size() == storeCount
			&& stored.stores().stream().noneMatch(store -> courseStoreIds.contains(store.storeId()));
	}

	private RecommendationResponse buildAreaRecommendation(Long memberId, Course course, CourseArea area, short day,
			UserTasteProfile profile, int profileVersion, Set<Long> courseStoreIds, int storeCount) {
		List<CandidateStoreRow> candidates = candidateRepository.findAreaCandidates(
			area.getCenterLat(), area.getCenterLng(), area.getRadiusM(), area.getRegion2nd(), area.getRegion3rd(),
			courseStoreIds, recommendationProperties.getArea().getCandidateLimit());
		RecommendationAreaSummary areaSummary = new RecommendationAreaSummary(area.getId(), area.getName());
		if (candidates.isEmpty()) {
			// 저장할 게 없다 — 재사용될 결과도 없고 반응을 받을 것도 없다
			return RecommendationResponse.of(null, new RecommendationResult(titleOf(area, profile), areaSummary, 0,
				false, List.of()));
		}
		List<CandidateStoreRow> ordered = nearestNeighborOrder(
			candidates.subList(0, Math.min(storeCount, candidates.size())), area.getCenterLat(),
			area.getCenterLng());
		List<RecommendedStoreResponse> stores = new ArrayList<>();
		int totalDistanceM = 0;
		for (int i = 0; i < ordered.size(); i++) {
			CandidateStoreRow row = ordered.get(i);
			Integer distanceToNext = null;
			if (i + 1 < ordered.size()) {
				CandidateStoreRow next = ordered.get(i + 1);
				distanceToNext = GeoDistance.metersBetween(row.latitude(), row.longitude(), next.latitude(),
					next.longitude());
				totalDistanceM += distanceToNext;
			}
			stores.add(new RecommendedStoreResponse(row.storeId(), row.name(), row.region3rd(),
				RecommendationMessage.RULE_REASON,
				NO_TASTE_FIT, distanceToNext, row.latitude(), row.longitude(), row.thumbnailKey()));
		}
		RecommendationResult result = new RecommendationResult(titleOf(area, profile), areaSummary, totalDistanceM,
			false, stores);
		Recommendation saved = recommendationRepository.save(Recommendation.forArea(memberId, course.getId(),
			area.getId(), day, profileVersion, Recommendation.RULE_PROVIDER, null, null, false, write(result)));
		return RecommendationResponse.of(saved.getId(), result);
	}

	public AnchorRecommendationResponse recommendNearAnchor(Long memberId, Long courseId, Long anchorStoreId,
			int page) {
		Course course = findCourse(courseId);
		courseAccessPolicy.requireViewable(course, memberId, null);
		List<CourseStore> activeStores = courseStoreRepository
			.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId);
		CourseStore anchorStop = activeStores.stream()
			.filter(courseStore -> courseStore.getStore().getId().equals(anchorStoreId))
			.findFirst()
			.orElseThrow(() -> new RecommendationException(RecommendationErrorCode.ANCHOR_STORE_NOT_IN_COURSE));
		Store anchor = anchorStop.getStore();
		// 앵커 추천도 그 일차의 동선이다 — 앵커가 담긴 일차를 키로 쓴다. 앵커를 다른 일차로 옮기면 다시 만든다
		short day = (short)anchorStop.getDay().intValue();
		int profileVersion = userTasteProfileRepository.findById(memberId)
			.map(UserTasteProfile::getVersion)
			.orElse(Recommendation.NO_PROFILE_VERSION);

		Set<Long> courseStoreIds = storeIdsOf(activeStores);
		Recommendation recommendation = recommendationRepository
			.findLatestAnchorForReuse(memberId, courseId, anchorStoreId, day, profileVersion)
			.orElse(null);
		AnchorRecommendationResult result;
		if (recommendation != null) {
			// 순위를 만든 뒤 담긴 가게는 뺀다 — 순서는 서로 독립이라 목록에서 빼도 나머지는 그대로 유효하다
			result = withoutCourseStores(read(recommendation, AnchorRecommendationResult.class), courseStoreIds);
		} else {
			result = rankNearAnchor(anchor, courseStoreIds);
			if (!result.stores().isEmpty()) {
				recommendation = recommendationRepository.save(Recommendation.forAnchor(memberId, courseId,
					anchorStoreId, day, profileVersion, write(result)));
			}
		}
		return page(recommendation != null ? recommendation.getId() : null, result, Math.max(page, 0),
			recommendationProperties.getAnchor().getPageSize());
	}

	private static AnchorRecommendationResult withoutCourseStores(AnchorRecommendationResult stored,
			Set<Long> courseStoreIds) {
		return new AnchorRecommendationResult(stored.anchor(), stored.stores().stream()
			.filter(store -> !courseStoreIds.contains(store.storeId()))
			.toList());
	}

	private AnchorRecommendationResult rankNearAnchor(Store anchor, Set<Long> courseStoreIds) {
		RecommendationProperties.Anchor anchorRules = recommendationProperties.getAnchor();
		List<CandidateStoreRow> candidates = candidateRepository.findAnchorCandidates(
			anchor.getLocation().getY(), anchor.getLocation().getX(), anchorRules.getRingM(),
			anchorRules.getMaxRadiusM(),
			courseStoreIds, anchorRules.getRankingSize());
		List<AnchorRecommendedStoreResponse> stores = candidates.stream()
			.map(row -> new AnchorRecommendedStoreResponse(row.storeId(), row.name(), (int)Math.round(row.distanceM()),
				AnchorReasonTag.SIGNATURE.label(), NO_TASTE_FIT, row.thumbnailKey()))
			.toList();
		return new AnchorRecommendationResult(new AnchorStoreSummary(anchor.getId(), anchor.getName()), stores);
	}

	private static AnchorRecommendationResponse page(Long recommendationId, AnchorRecommendationResult result,
			int page, int pageSize) {
		int from = Math.min(page * pageSize, result.stores().size());
		int to = Math.min(from + pageSize, result.stores().size());
		return new AnchorRecommendationResponse(recommendationId, result.anchor(), result.stores().subList(from, to),
			to < result.stores().size());
	}

	private Course findCourse(Long courseId) {
		return courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
	}

	/** 첫 빈 일차 — 정류장이 없는 가장 앞 일차. 전부 찼으면 마지막 일차. */
	static int firstEmptyDay(Course course, List<CourseStore> activeStores) {
		Set<Integer> occupied = activeStores.stream().map(CourseStore::getDay).collect(Collectors.toSet());
		for (int day = 1; day <= course.getDayCount(); day++) {
			if (!occupied.contains(day)) {
				return day;
			}
		}
		return Math.max(course.getDayCount(), 1);
	}

	private static String titleOf(CourseArea area, UserTasteProfile profile) {
		String typeLabel = profile != null && profile.hasType()
			? BbtiType.fromCode(profile.getTypeCode()).getLabel()
			: null;
		return RecommendationMessage.courseTitle(area.getName(), typeLabel);
	}

	/** 중심에서 가장 가까운 곳부터 시작해 매번 남은 것 중 가장 가까운 곳으로 — 동선만 정리한다(다양성은 #661). */
	static List<CandidateStoreRow> nearestNeighborOrder(List<CandidateStoreRow> picked, double fromLat,
			double fromLng) {
		List<CandidateStoreRow> remaining = new ArrayList<>(picked);
		List<CandidateStoreRow> ordered = new ArrayList<>();
		double lat = fromLat;
		double lng = fromLng;
		while (!remaining.isEmpty()) {
			final double curLat = lat;
			final double curLng = lng;
			CandidateStoreRow nearest = remaining.stream()
				.min((a, b) -> Integer.compare(
					GeoDistance.metersBetween(curLat, curLng, a.latitude(), a.longitude()),
					GeoDistance.metersBetween(curLat, curLng, b.latitude(), b.longitude())))
				.orElseThrow();
			remaining.remove(nearest);
			ordered.add(nearest);
			lat = nearest.latitude();
			lng = nearest.longitude();
		}
		return ordered;
	}

	private static Set<Long> storeIdsOf(List<CourseStore> activeStores) {
		return activeStores.stream().map(courseStore -> courseStore.getStore().getId()).collect(Collectors.toSet());
	}

	private Map<String, Object> write(Object result) {
		return objectMapper.convertValue(result,
			objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
	}

	private <T> T read(Recommendation recommendation, Class<T> type) {
		return objectMapper.convertValue(recommendation.getResult(), type);
	}
}

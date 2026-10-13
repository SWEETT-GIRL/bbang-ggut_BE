package sweetgirl.croissant.recommendation.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.common.util.GeoDistance;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseStore;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.personalization.repository.UserTasteProfileRepository;
import sweetgirl.croissant.recommendation.code.RecommendationErrorCode;
import sweetgirl.croissant.recommendation.code.RecommendationMessage;
import sweetgirl.croissant.recommendation.config.RecommendationProperties;
import sweetgirl.croissant.recommendation.dto.request.RecommendationRequest;
import sweetgirl.croissant.recommendation.dto.response.NextAreaResponse;
import sweetgirl.croissant.recommendation.dto.response.PolygonRecommendationScope;
import sweetgirl.croissant.recommendation.dto.response.RecommendationAreaSummary;
import sweetgirl.croissant.recommendation.dto.response.RecommendationResponse;
import sweetgirl.croissant.recommendation.dto.response.RecommendationResult;
import sweetgirl.croissant.recommendation.dto.response.RecommendedStoreResponse;
import sweetgirl.croissant.recommendation.entity.CourseArea;
import sweetgirl.croissant.recommendation.entity.Recommendation;
import sweetgirl.croissant.recommendation.entity.RecommendationFeedback;
import sweetgirl.croissant.recommendation.enums.FeedbackAction;
import sweetgirl.croissant.recommendation.enums.RecommendationMode;
import sweetgirl.croissant.recommendation.exception.RecommendationException;
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;
import sweetgirl.croissant.recommendation.repository.CourseAreaRepository;
import sweetgirl.croissant.recommendation.repository.PolygonAreaBindingRepository;
import sweetgirl.croissant.recommendation.repository.RecommendationCandidateJdbcRepository;
import sweetgirl.croissant.recommendation.repository.RecommendationFeedbackRepository;
import sweetgirl.croissant.recommendation.repository.RecommendationRepository;
import sweetgirl.croissant.recommendation.repository.TasteQuery;

/** 접근 권한과 요청 개수는 진입 서비스에서 검사한다. 지역 자료가 준비된 요청만 처리한다. */
@Service
@RequiredArgsConstructor
@Transactional
public class PolygonRecommendationCommandService {
	private static final String ALGORITHM = "polygon-hybrid-v1";
	private final PolygonAreaBindingRepository bindingRepository;
	private final RecommendationCandidateJdbcRepository candidateRepository;
	private final CourseAreaRepository areaRepository;
	private final CourseStoreRepository courseStoreRepository;
	private final UserTasteProfileRepository profileRepository;
	private final RecommendationRepository recommendationRepository;
	private final RecommendationFeedbackRepository feedbackRepository;
	private final RecommendationProperties properties;
	private final PolygonCoursePlanner planner;
	private final ReasonMetrics reasonMetrics;
	private final ObjectMapper objectMapper;

	public Optional<RecommendationResponse> tryRecommend(Long memberId, Course course, RecommendationRequest request,
			int count, String sessionId) {
		Recommendation previous = request.previousRecommendationId() == null ? null
			: ownedPrevious(memberId, course, request);
		RecommendationResult previousResult = previous == null ? null : read(previous);
		if (previousResult != null && previousResult.polygonScope() == null) {
			return Optional.empty();
		}
		CourseArea area = areaRepository.findByIdAndIsActiveTrue(request.areaId())
			.filter(found -> found.getSido().equals(course.getRegion()))
			.orElseThrow(() -> new RecommendationException(RecommendationErrorCode.COURSE_AREA_NOT_FOUND));
		String version = previousResult == null ? properties.getPolygon().getVersionKey()
			: previousResult.polygonScope().boundaryVersion();
		Optional<Long> binding = bindingRepository.findReadyBinding(version, area.getId());
		if (binding.isEmpty()) {
			return Optional.empty();
		}
		List<CourseStore> active = courseStoreRepository
			.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(course.getId());
		short day = previous == null ? CourseRecommendationCommandService.resolveDay(request.day(), course, active)
			: CourseRecommendationCommandService.resolveDay((int)previous.getDay(), course, active);
		var profile = profileRepository.findById(memberId).orElse(null);
		int profileVersion = profile == null ? Recommendation.NO_PROFILE_VERSION : profile.getVersion();
		var origin = origin(active, day);
		Set<Long> inCourse = active.stream().map(stop -> stop.getStore().getId()).collect(Collectors.toSet());
		Context context = new Context(memberId, course, area, day, TasteQuery.of(profile), profileVersion, count,
			sessionId, origin.orElse(null), inCourse);
		Set<Long> shown = shown(context);
		if (previous != null) {
			feedbackRepository.save(RecommendationFeedback.of(previous.getId(), null, FeedbackAction.RETRY));
			reasonMetrics.recordFeedback(FeedbackAction.RETRY.name(), ReasonMetrics.TasteMaterial.of(context.taste(),
				properties.getScore().getMinStrongStoresForTagReason()));
			return Optional.of(build(context, previousResult.polygonScope(), true, union(inCourse, shown), Set.of()));
		}
		PolygonRecommendationScope initial = initialScope(area, binding.get(), version, request.isExactArea(),
			context.origin());
		Optional<Recommendation> latest = recommendationRepository.findLatestAreaForReuse(memberId, course.getId(),
			area.getId(), day, profileVersion);
		if (latest.isPresent()) {
			RecommendationResult stored = read(latest.get());
			if (compatible(stored.polygonScope(), initial) && sameSitting(latest.get(), sessionId)) {
				List<CandidateStoreRow> available = candidates(context, stored.polygonScope(), true, inCourse,
					context.taste());
				if (valid(stored, available, context)) {
					return Optional.of(RecommendationResponse.of(latest.get().getId(), stored));
				}
				Set<Long> retained = latest.get().shownStoreIds();
				shown.removeAll(retained);
				return Optional.of(build(context, stored.polygonScope(), true, union(inCourse, shown), retained));
			}
		}
		// 다른 칩·프로필 버전에서 본 가게도 제외한다. 다음 동네 요청은 소진되어도 본 가게로 되돌리지 않는다.
		if (!shown.isEmpty()) {
			RecommendationResponse fresh = build(context, initial, request.isExactArea(), union(inCourse, shown),
				Set.of());
			if (!fresh.stores().isEmpty() || request.isExactArea()) {
				return Optional.of(fresh);
			}
		}
		return Optional.of(build(context, initial, request.isExactArea(), inCourse, Set.of()));
	}

	private Recommendation ownedPrevious(Long memberId, Course course, RecommendationRequest request) {
		Recommendation previous = recommendationRepository
			.findByIdAndMemberId(request.previousRecommendationId(), memberId)
			.filter(found -> found.getCourseId().equals(course.getId()))
			.orElseThrow(() -> new RecommendationException(RecommendationErrorCode.RECOMMENDATION_NOT_FOUND));
		if (previous.isAnchor() || !Objects.equals(previous.getCourseAreaId(), request.areaId())) {
			throw new RecommendationException(RecommendationErrorCode.PREVIOUS_AREA_MISMATCH);
		}
		return previous;
	}

	private record Context(Long memberId, Course course, CourseArea area, short day, TasteQuery taste,
			int profileVersion, int count, String sessionId, PolygonCoursePlanner.Origin origin, Set<Long> inCourse) {
	}

	private RecommendationResponse build(Context context, PolygonRecommendationScope scope, boolean bounded,
			Set<Long> excluded, Set<Long> retained) {
		List<CandidateStoreRow> rows = candidates(context, scope, bounded, excluded, context.taste());
		boolean fallback = CourseRecommendationCommandService.isTasteFallback(rows, context.taste(),
			properties.getScore().getMinTasteCandidates());
		if (fallback) {
			rows = candidates(context, scope, bounded, excluded, TasteQuery.empty());
		}
		int budget = context.count() == 1 ? scope.singleStoreApproachM()
			: (context.count() - 1) * scope.distancePerLegM();
		List<CandidateStoreRow> ordered = planner.plan(rows, context.count(), scope.maxHopM(), budget, context.origin(),
			retained);
		PolygonRecommendationScope savedScope = freeze(scope, ordered, bounded, context.origin());
		List<GeneratedReasonSentence> reasons = ReasonSentenceGenerator.generate(context.taste(), ordered,
			properties.getScore());
		reasonMetrics.recordGenerated(reasons, context.taste(), properties.getScore().getMinStrongStoresForTagReason());
		List<RecommendedStoreResponse> stores = new ArrayList<>();
		int total = 0;
		for (int i = 0; i < ordered.size(); i++) {
			CandidateStoreRow row = ordered.get(i);
			Integer distance = i + 1 < ordered.size() ? PolygonCoursePlanner.distance(row, ordered.get(i + 1)) : null;
			if (distance != null) {
				total += distance;
			}
			stores.add(new RecommendedStoreResponse(row.storeId(), row.name(), row.region3rd(), reasons.get(i).text(),
				CourseRecommendationCommandService.tasteFitOf(row), distance, row.latitude(), row.longitude(),
				row.thumbnailKey()));
		}
		String name = scope.exactArea() ? context.area().getName() : context.area().getPassportSpot();
		Set<Long> remainingExcluded = union(excluded,
			ordered.stream().map(CandidateStoreRow::storeId).collect(Collectors.toSet()));
		boolean last = ordered.isEmpty()
			|| planner.plan(candidates(context, savedScope, true, remainingExcluded, context.taste()),
				1, scope.maxHopM(), budget, context.origin(), Set.of()).isEmpty();
		RecommendationResult result = new RecommendationResult(RecommendationMessage.courseTitle(name),
			new RecommendationAreaSummary(context.area().getId(), name), total, fallback, context.count(), stores, last,
			savedScope);
		if (stores.isEmpty()) {
			return RecommendationResponse.of(null, result, nextArea(context),
				context.origin() == null ? null : context.origin().storeId());
		}
		Recommendation saved = recommendationRepository
			.save(Recommendation.forArea(context.memberId(), context.course().getId(),
				context.area().getId(), context.day(), context.profileVersion(), Recommendation.RULE_PROVIDER, null,
				null, false,
				objectMapper.convertValue(result,
					objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)),
				context.sessionId()));
		return RecommendationResponse.of(saved.getId(), result);
	}

	private List<CandidateStoreRow> candidates(Context context, PolygonRecommendationScope scope, boolean bounded,
			Set<Long> excluded, TasteQuery taste) {
		return candidateRepository.findPolygonCandidates(context.memberId(), taste, scope.bindingId(),
			scope.latitude(), scope.longitude(), bounded ? scope.radiusM() : null, excluded);
	}

	private NextAreaResponse nextArea(Context context) {
		Set<Long> visited = new HashSet<>(
			recommendationRepository.findRecommendedAreaIds(context.memberId(), context.course().getId()));
		visited.add(context.area().getId());
		List<CourseArea> areas = new ArrayList<>(areaRepository
			.findAllBySidoAndIsActiveTrueOrderByDisplayOrderAscNameAsc(context.area().getSido()));
		areas.sort(
			java.util.Comparator.comparing(area -> !area.getPassportSpot().equals(context.area().getPassportSpot())));
		for (CourseArea area : areas) {
			if (visited.contains(area.getId())) {
				continue;
			}
			Optional<Long> binding = bindingRepository.findReadyBinding(properties.getPolygon().getVersionKey(),
				area.getId());
			if (binding.isEmpty()) {
				continue;
			}
			Set<Long> excluded = union(context.inCourse(), shown(context));
			List<CandidateStoreRow> rows = candidateRepository.findPolygonCandidates(context.memberId(),
				context.taste(), binding.get(),
				area.getCenterLat(), area.getCenterLng(), area.getRadiusM(), excluded);
			int budget = context.count() == 1 ? properties.getPolygon().getSingleStoreApproachM()
				: (context.count() - 1) * properties.getPolygon().getDistancePerLegM();
			if (!planner.plan(rows, 1, area.getMaxHopM(), budget, context.origin(), Set.of()).isEmpty()) {
				return new NextAreaResponse(area.getId(), area.getName(), rows.size());
			}
		}
		return null;
	}

	private Set<Long> shown(Context context) {
		Set<Long> result = new HashSet<>();
		recommendationRepository.findAllByMemberIdAndCourseIdAndModeAndDay(context.memberId(), context.course().getId(),
			RecommendationMode.AREA, context.day()).forEach(row -> result.addAll(row.shownStoreIds()));
		return result;
	}

	private boolean sameSitting(Recommendation recommendation, String sessionId) {
		LocalDateTime created = recommendation.getCreatedAt();
		if (created == null) {
			return false;
		}
		return created.isAfter(LocalDateTime.now().minusMinutes(properties.getArea().getSessionWindowMinutes()))
			|| (sessionId != null && sessionId.equals(recommendation.getSessionId())
				&& created.isAfter(LocalDateTime.now().minusHours(properties.getArea().getSessionMaxAgeHours())));
	}

	private boolean compatible(PolygonRecommendationScope stored, PolygonRecommendationScope requested) {
		return stored != null && stored.bindingId() == requested.bindingId()
			&& stored.algorithmVersion().equals(ALGORITHM) && stored.exactArea() == requested.exactArea();
	}

	private boolean valid(RecommendationResult stored, List<CandidateStoreRow> available, Context context) {
		PolygonRecommendationScope scope = stored.polygonScope();
		var origin = context.origin();
		if (stored.storeCount() != context.count()
			|| !Objects.equals(scope.originStoreId(), origin == null ? null : origin.storeId())
			|| !Objects.equals(scope.originLatitude(), origin == null ? null : origin.latitude())
			|| !Objects.equals(scope.originLongitude(), origin == null ? null : origin.longitude())) {
			return false;
		}
		Map<Long, CandidateStoreRow> byId = available.stream()
			.collect(Collectors.toMap(CandidateStoreRow::storeId, row -> row));
		return !stored.stores().isEmpty() && stored.stores().stream().allMatch(store -> {
			CandidateStoreRow row = byId.get(store.storeId());
			return row != null && row.latitude() == store.latitude() && row.longitude() == store.longitude();
		});
	}

	private PolygonRecommendationScope initialScope(CourseArea area, long bindingId, String version, boolean exact,
			PolygonCoursePlanner.Origin origin) {
		var policy = properties.getPolygon();
		if (policy.getDistancePerLegM() <= 0 || policy.getRetryRadiusM() <= 0
			|| policy.getSingleStoreApproachM() <= 0) {
			throw new IllegalStateException("폴리곤 추천 거리 설정은 양수여야 합니다.");
		}
		return new PolygonRecommendationScope(ALGORITHM, version, bindingId, area.getCenterLat(), area.getCenterLng(),
			exact ? area.getRadiusM() : policy.getRetryRadiusM(), area.getMaxHopM(), policy.getDistancePerLegM(),
			policy.getSingleStoreApproachM(), exact, origin == null ? null : origin.storeId(),
			origin == null ? null : origin.latitude(), origin == null ? null : origin.longitude());
	}

	private PolygonRecommendationScope freeze(PolygonRecommendationScope scope, List<CandidateStoreRow> route,
			boolean bounded, PolygonCoursePlanner.Origin origin) {
		double lat = bounded || route.isEmpty() ? scope.latitude() : route.get(0).latitude();
		double lng = bounded || route.isEmpty() ? scope.longitude() : route.get(0).longitude();
		int radius = scope.radiusM();
		if (!bounded) {
			// geodesic SQL과 구면 근사의 차이까지 포함하여 최초 경로 전부가 재사용 범위 안에 있도록 여유를 둔다.
			for (CandidateStoreRow row : route) {
				radius = Math.max(radius,
					(int)Math.ceil(GeoDistance.metersBetween(lat, lng, row.latitude(), row.longitude()) * 1.01) + 2);
			}
		}
		return new PolygonRecommendationScope(scope.algorithmVersion(), scope.boundaryVersion(), scope.bindingId(), lat,
			lng,
			radius, scope.maxHopM(), scope.distancePerLegM(), scope.singleStoreApproachM(), scope.exactArea(),
			origin == null ? null : origin.storeId(), origin == null ? null : origin.latitude(),
			origin == null ? null : origin.longitude());
	}

	private Optional<PolygonCoursePlanner.Origin> origin(List<CourseStore> active, short day) {
		return active.stream().filter(stop -> stop.getDay() != null && stop.getDay() == day)
			.reduce((first, second) -> second).map(CourseStore::getStore)
			.filter(store -> store.getLocation() != null)
			.map(store -> new PolygonCoursePlanner.Origin(store.getId(), store.getLocation().getY(),
				store.getLocation().getX()));
	}

	private Set<Long> union(Set<Long> first, Set<Long> second) {
		Set<Long> result = new HashSet<>(first);
		result.addAll(second);
		return result;
	}

	private RecommendationResult read(Recommendation recommendation) {
		return objectMapper.convertValue(recommendation.getResult(), RecommendationResult.class);
	}
}

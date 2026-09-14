package sweetgirl.croissant.recommendation.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.common.util.GeoDistance;
import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.dto.response.CourseStoreResponse;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseStore;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.course.service.CourseAccessPolicy;
import sweetgirl.croissant.course.service.CourseCommandService;
import sweetgirl.croissant.personalization.entity.UserTasteProfile;
import sweetgirl.croissant.personalization.enums.BbtiType;
import sweetgirl.croissant.personalization.repository.UserTasteProfileRepository;
import sweetgirl.croissant.recommendation.code.RecommendationErrorCode;
import sweetgirl.croissant.recommendation.code.RecommendationMessage;
import sweetgirl.croissant.recommendation.config.RecommendationProperties;
import sweetgirl.croissant.recommendation.dto.request.CourseStoreBulkAddRequest;
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
import sweetgirl.croissant.recommendation.repository.AreaChipStats;
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
 * 상권: 반경 안 ACTIVE 가게를 인기순으로 15곳 뽑고, 인기 1위에서 시작해 구간 상한(course_area.max_hop_m) 안에서 가장 가까운
 * 가게를 이어 붙여 N 곳을 만든다 — 상한 안에 더 없으면 있는 만큼. 인기 상위 N 곳을 먼저 확정하면 넓은 시군구에서 코스가
 * 수십 km 로 벌어진다(PR #735 리뷰). 이유는 템플릿, tasteFit 은 축이 없어 0.
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
	private final CourseCommandService courseCommandService;
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
		CourseArea requested = courseAreaRepository.findByIdAndIsActiveTrue(request.areaId())
			.filter(found -> found.getSido().equals(course.getRegion()))
			.orElseThrow(() -> new RecommendationException(RecommendationErrorCode.COURSE_AREA_NOT_FOUND));

		List<CourseStore> activeStores = courseStoreRepository
			.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId);
		short day = resolveDay(request.day(), course, activeStores);
		CourseArea area = resolveNeighborhood(memberId, requested, activeStores, day);
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
	 * 도장 칸 아래 동네가 여럿이면 서버가 고른다(#736) — 칩은 도장 칸 하나라 사용자가 동네를 고르지 않는다.
	 * ① 추천받는 일차에 담긴 가게가 반경 안인 동네 ② 그 일차가 비어 있으면 코스 전체에서 담긴 가게가 많은 동네
	 * ③ 내 저장 가게가 있는 동네 ④ 대표 행(display_order 첫 행). ①②는 같으면 대표 행 순.
	 * 일차를 먼저 보는 이유: 1일차 중문·2일차 성산인 코스에서 2일차를 짜는 사람에겐 성산이 맞다 — 코스 전체로 세면 중문이 된다.
	 * 코스 전체로 넘어가는 건 그 일차가 정말 빈 경우뿐이다 — 일차에 가게는 있는데 어느 동네 반경에도 안 들어가면(남원읍 가게)
	 * 다른 날의 동선이 오늘을 덮지 않게 ③으로 간다(PR #737 리뷰).
	 * 요청이 대표 행이 아닌 동네 행이면(다음 칸 제안 등으로 동네를 집어 온 것) 그대로 쓴다. 동네가 하나뿐인 칸은 조회 한 번으로 끝난다.
	 */
	private CourseArea resolveNeighborhood(Long memberId, CourseArea requested, List<CourseStore> activeStores,
			short day) {
		List<CourseArea> neighborhoods = courseAreaRepository
			.findAllBySidoAndPassportSpotAndIsActiveTrueOrderByDisplayOrderAscNameAsc(requested.getSido(),
				requested.getPassportSpot());
		if (neighborhoods.size() <= 1 || !neighborhoods.get(0).getId().equals(requested.getId())) {
			return requested;
		}
		List<CourseStore> sameDayStores = activeStores.stream()
			.filter(courseStore -> courseStore.getDay() != null && courseStore.getDay() == day)
			.toList();
		Optional<CourseArea> byStores = sameDayStores.isEmpty()
			? neighborhoodWithMostStores(neighborhoods, activeStores)
			: neighborhoodWithMostStores(neighborhoods, sameDayStores);
		if (byStores.isPresent()) {
			return byStores.get();
		}
		Map<Long, AreaChipStats> stats = candidateRepository.findAreaChipStats(memberId, neighborhoods);
		return neighborhoods.stream()
			.filter(neighborhood -> stats.containsKey(neighborhood.getId())
				&& stats.get(neighborhood.getId()).hasSavedStore())
			.findFirst()
			.orElse(neighborhoods.get(0));
	}

	/** 반경 안에 담긴 가게가 가장 많은 동네. 한 곳도 없으면 비어 있다. 같으면 앞(대표 행) 순. */
	private static Optional<CourseArea> neighborhoodWithMostStores(List<CourseArea> neighborhoods,
			List<CourseStore> courseStores) {
		CourseArea best = null;
		int bestCount = 0;
		for (CourseArea neighborhood : neighborhoods) {
			int count = 0;
			for (CourseStore courseStore : courseStores) {
				Point location = courseStore.getStore().getLocation();
				if (location != null && neighborhood.covers(location.getY(), location.getX())) {
					count++;
				}
			}
			if (count > bestCount) {
				best = neighborhood;
				bestCount = count;
			}
		}
		return Optional.ofNullable(best);
	}

	/**
	 * 저장된 결과를 다시 써도 되는지. 그 사이 코스가 바뀌어 담긴 가게가 결과에 들어 있거나(담으면 중복 오류가 난다),
	 * 요청한 가게 수가 다르면 다시 만든다. 후보가 모자라 결과가 짧은 건 다시 만들 이유가 아니라 stores.size() 가 아니라
	 * 저장해 둔 요청 수와 비교한다. 새 행이 최신이 되어 다음부터는 그것이 재사용된다.
	 */
	private static boolean isStillValid(RecommendationResult stored, Set<Long> courseStoreIds, int storeCount) {
		return stored.storeCount() == storeCount
			&& stored.stores().stream().noneMatch(store -> courseStoreIds.contains(store.storeId()));
	}

	private RecommendationResponse buildAreaRecommendation(Long memberId, Course course, CourseArea area, short day,
			UserTasteProfile profile, int profileVersion, Set<Long> courseStoreIds, int storeCount) {
		List<CandidateStoreRow> candidates = candidateRepository.findAreaCandidates(
			area.getCenterLat(), area.getCenterLng(), area.getRadiusM(), area.getSido(), area.getRegion2nd(),
			area.getRegion3rd(), courseStoreIds, recommendationProperties.getArea().getCandidateLimit());
		RecommendationAreaSummary areaSummary = new RecommendationAreaSummary(area.getId(), area.getName());
		if (candidates.isEmpty()) {
			// 저장할 게 없다 — 재사용될 결과도 없고 반응을 받을 것도 없다
			return RecommendationResponse.of(null, new RecommendationResult(titleOf(area, profile), areaSummary, 0,
				false, storeCount, List.of()));
		}
		List<CandidateStoreRow> ordered = chainWithinHop(candidates, area.getMaxHopM(), storeCount);
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
			false, storeCount, stores);
		Recommendation saved = recommendationRepository.save(Recommendation.forArea(memberId, course.getId(),
			area.getId(), day, profileVersion, Recommendation.RULE_PROVIDER, null, null, false, write(result)));
		return RecommendationResponse.of(saved.getId(), result);
	}

	public AnchorRecommendationResponse recommendNearAnchor(Long memberId, Long courseId, Long anchorStoreId,
			int page) {
		Course course = findCourse(courseId);
		courseAccessPolicy.requireEditable(course, memberId);
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

	/**
	 * 추천 결과를 한 번에 담는다. 추천이 내 것이고 이 코스의 것인지는 여기서 확인하고(남의 것·다른 코스 것은 404),
	 * 담기 자체는 course 모듈이 한다 — course 는 추천을 모른다.
	 */
	public List<CourseStoreResponse> addRecommendedStores(Long memberId, Long courseId,
			CourseStoreBulkAddRequest request) {
		Long recommendationId = request.getRecommendationId();
		if (recommendationId != null) {
			recommendationRepository.findByIdAndMemberId(recommendationId, memberId)
				.filter(recommendation -> courseId.equals(recommendation.getCourseId()))
				.orElseThrow(() -> new RecommendationException(RecommendationErrorCode.RECOMMENDATION_NOT_FOUND));
		}
		return courseCommandService.addStores(memberId, courseId, request.getStores(), recommendationId);
	}

	private Course findCourse(Long courseId) {
		return courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
	}

	/**
	 * 요청 일차가 있으면 범위 검사, 없으면 첫 빈 일차. 허용 범위는 코스가 아는 일차 수(dayCount · 날짜 span)와
	 * 담긴 마지막 일차 + 1 중 큰 것까지 — 단건 담기의 resolveDay 와 같은 상한이고, 날짜가 있는 코스는 그 날짜 안 어느 일차든 된다.
	 * course 의 resolveDay 와 같은 규칙이다(PR #733).
	 */
	static short resolveDay(Integer requestedDay, Course course, List<CourseStore> activeStores) {
		int maxStoreDay = activeStores.stream().mapToInt(CourseStore::getDay).max().orElse(0);
		int allowedMaxDay = Math.max(knownDayCount(course), maxStoreDay + 1);
		if (requestedDay == null) {
			return (short)firstEmptyDay(course, activeStores);
		}
		if (requestedDay < 1 || requestedDay > allowedMaxDay) {
			throw new RecommendationException(RecommendationErrorCode.DAY_OUT_OF_RANGE);
		}
		return requestedDay.shortValue();
	}

	/** 코스가 아는 일차 수 — 서버의 dayCount 와 날짜 span 중 큰 것. 앱은 날짜로 탭을 그리는데 dayCount 는 생성 때 1 이라 둘이 다를 수 있다. */
	static int knownDayCount(Course course) {
		return Math.max(Math.max(course.getDayCount(), Course.dateSpan(course.getStartDate(), course.getEndDate())), 1);
	}

	/** 첫 빈 일차 — 정류장이 없는 가장 앞 일차. 전부 찼으면 마지막 일차. */
	static int firstEmptyDay(Course course, List<CourseStore> activeStores) {
		Set<Integer> occupied = activeStores.stream().map(CourseStore::getDay).collect(Collectors.toSet());
		int dayCount = knownDayCount(course);
		for (int day = 1; day <= dayCount; day++) {
			if (!occupied.contains(day)) {
				return day;
			}
		}
		return dayCount;
	}

	private static String titleOf(CourseArea area, UserTasteProfile profile) {
		String typeLabel = profile != null && profile.hasType()
			? BbtiType.fromCode(profile.getTypeCode()).getLabel()
			: null;
		return RecommendationMessage.courseTitle(area.getName(), typeLabel);
	}

	/** 중심에서 가장 가까운 곳부터 시작해 매번 남은 것 중 가장 가까운 곳으로 — 동선만 정리한다(다양성은 #661). */
	/**
	 * 순위(인기순) 후보에서 코스를 만든다: 1위에서 시작해, 지금 가게에서 maxHopM 안에 있는 남은 후보 중 가장 가까운 가게를
	 * 다음으로 잇는다(같은 거리면 순위가 높은 쪽). 상한 안에 후보가 없으면 거기서 멈춘다 — 있는 만큼만 돌려준다.
	 * 반경 안 후보라도 서로는 지름만큼 떨어질 수 있어 상한은 여기서 지킨다. 랩 테스트가 시드 48칸 전부로 이 불변 조건을 확인한다.
	 */
	public static List<CandidateStoreRow> chainWithinHop(List<CandidateStoreRow> ranked, int maxHopM, int storeCount) {
		if (ranked.isEmpty() || storeCount <= 0) {
			return List.of();
		}
		List<CandidateStoreRow> remaining = new ArrayList<>(ranked);
		List<CandidateStoreRow> ordered = new ArrayList<>();
		CandidateStoreRow current = remaining.remove(0);
		ordered.add(current);
		while (ordered.size() < storeCount && !remaining.isEmpty()) {
			CandidateStoreRow next = null;
			int nextDistance = Integer.MAX_VALUE;
			for (CandidateStoreRow candidate : remaining) {
				int distance = GeoDistance.metersBetween(current.latitude(), current.longitude(),
					candidate.latitude(), candidate.longitude());
				if (distance <= maxHopM && distance < nextDistance) {
					next = candidate;
					nextDistance = distance;
				}
			}
			if (next == null) {
				break;
			}
			remaining.remove(next);
			ordered.add(next);
			current = next;
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

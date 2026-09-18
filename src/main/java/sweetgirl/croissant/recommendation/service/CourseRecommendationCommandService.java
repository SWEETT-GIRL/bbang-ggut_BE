package sweetgirl.croissant.recommendation.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToIntFunction;
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
import sweetgirl.croissant.recommendation.dto.response.NextAreaResponse;
import sweetgirl.croissant.recommendation.dto.response.RecommendationAreaSummary;
import sweetgirl.croissant.recommendation.dto.response.RecommendationResponse;
import sweetgirl.croissant.recommendation.dto.response.RecommendationResult;
import sweetgirl.croissant.recommendation.dto.response.RecommendedStoreResponse;
import sweetgirl.croissant.recommendation.entity.CourseArea;
import sweetgirl.croissant.recommendation.entity.Recommendation;
import sweetgirl.croissant.recommendation.entity.RecommendationFeedback;
import sweetgirl.croissant.recommendation.enums.AnchorReasonTag;
import sweetgirl.croissant.recommendation.enums.FeedbackAction;
import sweetgirl.croissant.recommendation.enums.RecommendationMode;
import sweetgirl.croissant.recommendation.exception.RecommendationException;
import sweetgirl.croissant.recommendation.repository.AreaChipStats;
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;
import sweetgirl.croissant.recommendation.repository.CourseAreaRepository;
import sweetgirl.croissant.recommendation.repository.RecommendationCandidateJdbcRepository;
import sweetgirl.croissant.recommendation.repository.RecommendationFeedbackRepository;
import sweetgirl.croissant.recommendation.repository.RecommendationRepository;
import sweetgirl.croissant.recommendation.repository.TasteQuery;
import sweetgirl.croissant.store.entity.Store;

/**
 * 상권 추천(진입점 1)과 앵커 추천(진입점 2) — #660 의 룰만 도는 임시 구현. LLM 은 #662.
 *
 * 둘 다 재사용 키(회원·코스·상권 또는 앵커·일차·프로필 버전)의 최신 행을 다시 쓴다. 상권은 자리 기준이다(09-15): 같은 자리(같은
 * 세션 id 또는 30분 안)면 보던 묶음 그대로, 같은 자리인데 담긴 가게 등으로 무효면 보던 묶음의 빈 자리만 채우고, 다른 세션이면
 * 안 본 가게로 새 묶음, 안 본 가게가 없으면 첫 묶음을 다시 계산한다(reuseOrRefresh). 앵커는 그 사이 담긴 가게를 저장된 순위에서 뺀다.
 * 상권: 반경 안 ACTIVE 가게(프로필 있고 빵집다움 하한 이상)를 #661 점수순으로 15곳 뽑고, 1위에서 시작해 구간 상한(course_area.max_hop_m) 안에서 가장 가까운
 * 가게를 이어 붙여 N 곳을 만든다 — 상한 안에 더 없으면 있는 만큼. 인기 상위 N 곳을 먼저 확정하면 넓은 시군구에서 코스가
 * 수십 km 로 벌어진다(PR #735 리뷰). 이유는 템플릿, tasteFit 은 축이 없어 0.
 * 앵커: 앵커 좌표 1→2→3km 링 순(링 안은 점수순) 30곳을 행 하나에 저장하고 page 로 9곳씩 자른다. 이유 문장 없이 배지만(09-13).
 * 요청 본문에 여정 값은 없다. 세션 의도는 요청 헤더 X-Session-Id 와 요청 로그로 본다(#661) — 카드 직전 이벤트(sessionEvents)는
 * 그 역할이 옮겨 가서, 진입 화면(entryScreen)은 분석용인데 분석은 Amplitude 가 맡고 서버는 코스 데이터로 같은 사실을 알아서
 * 09-15 에 뺐다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CourseRecommendationCommandService {

	private final CourseRepository courseRepository;
	private final CourseStoreRepository courseStoreRepository;
	private final CourseAccessPolicy courseAccessPolicy;
	private final CourseCommandService courseCommandService;
	private final CourseAreaRepository courseAreaRepository;
	private final UserTasteProfileRepository userTasteProfileRepository;
	private final RecommendationRepository recommendationRepository;
	private final RecommendationFeedbackRepository recommendationFeedbackRepository;
	private final RecommendationCandidateJdbcRepository candidateRepository;
	private final RecommendationProperties recommendationProperties;
	private final ObjectMapper objectMapper;

	/**
	 * 새로 열기(previousRecommendationId 없음)의 재사용(09-15): 키의 최신 묶음이 있으면 reuseOrRefresh 의 세 갈래(같은 자리·유효 →
	 * 그대로, 같은 자리·무효 → 빈 자리만 채움, 다른 세션 → 안 본 묶음), 없거나 안 본 가게가 없으면 첫 묶음 — 소진된 묶음은 다시 주지 않는다.
	 */
	public RecommendationResponse recommend(Long memberId, Long courseId, RecommendationRequest request,
			String sessionId) {
		RecommendationProperties.Area areaRules = recommendationProperties.getArea();
		int storeCount = request.storeCount() != null ? request.storeCount() : areaRules.getDefaultStoreCount();
		if (storeCount < areaRules.getMinStoreCount() || storeCount > areaRules.getMaxStoreCount()) {
			throw new RecommendationException(RecommendationErrorCode.STORE_COUNT_OUT_OF_RANGE);
		}
		Course course = findCourse(courseId);
		courseAccessPolicy.requireEditable(course, memberId);
		if (request.previousRecommendationId() != null) {
			return retry(memberId, course, request, storeCount, sessionId);
		}
		CourseArea requested = courseAreaRepository.findByIdAndIsActiveTrue(request.areaId())
			.filter(found -> found.getSido().equals(course.getRegion()))
			.orElseThrow(() -> new RecommendationException(RecommendationErrorCode.COURSE_AREA_NOT_FOUND));

		List<CourseStore> activeStores = courseStoreRepository
			.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId);
		short day = resolveDay(request.day(), course, activeStores);
		UserTasteProfile profile = userTasteProfileRepository.findById(memberId).orElse(null);
		int profileVersion = profile != null ? profile.getVersion() : Recommendation.NO_PROFILE_VERSION;
		TasteQuery taste = TasteQuery.of(profile);
		// 다음 동네 제안(nextArea)에서 온 요청은 그 행을 그대로 쓴다 — 대표 행 id 는 칩 id 와 같아서, 표시가 없으면 칩으로 읽혀
		// 동네를 다시 고르고 신호가 그대로라 제안한 동네로 못 간다(PR #738 리뷰)
		CourseArea area = request.isExactArea()
			? requested
			: resolveNeighborhood(memberId, sessionId, taste, requested, activeStores, day);

		Set<Long> courseStoreIds = storeIdsOf(activeStores);
		Optional<RecommendationResponse> kept = recommendationRepository
			.findLatestAreaForReuse(memberId, courseId, area.getId(), day, profileVersion)
			.flatMap(latest -> reuseOrRefresh(latest, memberId, sessionId, course, area, day, profile, profileVersion,
				courseStoreIds, storeCount));
		if (kept.isPresent()) {
			return kept.get();
		}
		// 첫 묶음 — 최신 묶음이 없거나, 다 본 사람(안 본 가게 없음)이라 소진된 마지막 묶음 대신 처음부터. 그 뒤 다시 받기는 바로 다음 동네 제안
		return buildAreaRecommendation(memberId, sessionId, course, area, day, profile, profileVersion, activeStores,
			courseStoreIds, storeCount);
	}

	/**
	 * 최신 묶음이 있을 때 새로 열기의 세 갈래(09-15, PR #745 리뷰). 다른 세션이면 유효 여부와 무관하게 안 본 가게로 새 묶음 — 어제 B 에서
	 * 담고 오늘 열면 첫 묶음 A(어제 본 가게)가 아니라 안 본 가게여야 한다. RETRY 는 안 남긴다(누른 게 아니다). 같은 자리에서 유효하면
	 * 그대로. 같은 자리인데 무효(방금 담음 · 자격 상실 · 가게 수 변경)면 보던 묶음의 빈 자리만 채운다 — 다른 묶음에서 본 가게는 빼되
	 * 이 묶음의 가게는 다시 후보로 두어 자리를 잃지 않게. 안 본 가게가 없으면 empty — 호출자가 첫 묶음을 만든다.
	 */
	private Optional<RecommendationResponse> reuseOrRefresh(Recommendation latest, Long memberId, String sessionId,
			Course course, CourseArea area, short day, UserTasteProfile profile, int profileVersion,
			Set<Long> courseStoreIds, int storeCount) {
		if (!isSameSitting(latest, sessionId)) {
			Set<Long> excludeStoreIds = new HashSet<>(courseStoreIds);
			excludeStoreIds.addAll(shownStoreIdsOf(memberId, course.getId(), area.getId(), day));
			return refresh(memberId, sessionId, course, area, day, profile, profileVersion, excludeStoreIds,
				storeCount);
		}
		RecommendationResult stored = read(latest, RecommendationResult.class);
		Set<Long> unavailable = candidateRepository.findUnavailableStoreIds(memberId,
			stored.stores().stream().map(RecommendedStoreResponse::storeId).toList());
		if (isStillValid(stored, courseStoreIds, unavailable, storeCount)) {
			// 같은 자리에서 닫았다 열면 보던 묶음 그대로 — 가게 상세를 보고 돌아와 담으려는 사람의 자리를 지킨다
			return Optional.of(RecommendationResponse.of(latest.getId(), stored));
		}
		Set<Long> excludeStoreIds = new HashSet<>(courseStoreIds);
		Set<Long> shownElsewhere = shownStoreIdsOf(memberId, course.getId(), area.getId(), day);
		shownElsewhere.removeAll(latest.shownStoreIds());
		excludeStoreIds.addAll(shownElsewhere);
		return refresh(memberId, sessionId, course, area, day, profile, profileVersion, excludeStoreIds, storeCount);
	}

	/** 새 묶음이 있으면 조립해 저장하고, 안 본 가게가 없으면 empty — 소진 판정에 다음 동네 계산까지 돌리지 않는다(PR #745 리뷰). */
	private Optional<RecommendationResponse> refresh(Long memberId, String sessionId, Course course, CourseArea area,
			short day, UserTasteProfile profile, int profileVersion, Set<Long> excludeStoreIds, int storeCount) {
		CandidatePool pool = poolFor(memberId, TasteQuery.of(profile), area, excludeStoreIds);
		if (pool.candidates().isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(assemble(pool, memberId, sessionId, course, area, day, profile, profileVersion, storeCount));
	}

	/**
	 * 같은 자리인가 — 같은 앱 세션(X-Session-Id, 상한 sessionMaxAgeHours)이거나 마지막 묶음이 세션 창(sessionWindowMinutes) 안이다.
	 * 헤더 없는 옛 앱과 세션 id 가 없는 옛 행은 창만 본다. 앱은 백그라운드 5분 뒤 새 세션 id 를 만들므로 5~30분 사이에 돌아온
	 * 사람은 창으로 잡힌다(FE 합의 09-15).
	 */
	private boolean isSameSitting(Recommendation latest, String sessionId) {
		LocalDateTime createdAt = latest.getCreatedAt();
		if (createdAt == null) {
			return false;
		}
		RecommendationProperties.Area areaRules = recommendationProperties.getArea();
		LocalDateTime now = LocalDateTime.now();
		// 같은 세션 id 라도 하루가 넘으면 새 자리 — 앱을 며칠 켜 둔 사람과 세션 id 고정값 회귀에 대한 상한(칩 신호와 같은 값)
		boolean sameSession = sessionId != null && sessionId.equals(latest.getSessionId())
			&& createdAt.isAfter(now.minusHours(areaRules.getSessionMaxAgeHours()));
		return sameSession || createdAt.isAfter(now.minusMinutes(areaRules.getSessionWindowMinutes()));
	}

	/** 이 회원·코스·동네·일차에서 여태 보여준 가게 전부(프로필 버전 무관) — 다시 받기와 새 세션 새 묶음의 제외 재료. */
	private Set<Long> shownStoreIdsOf(Long memberId, Long courseId, Long areaId, short day) {
		Set<Long> shown = new HashSet<>();
		recommendationRepository
			.findAllByMemberIdAndCourseIdAndModeAndCourseAreaIdAndDay(memberId, courseId, RecommendationMode.AREA,
				areaId, day)
			.forEach(row -> shown.addAll(row.shownStoreIds()));
		return shown;
	}

	/**
	 * 다시 받기(#661): 동네를 다시 고르지 않고 이전 추천이 저장한 상권 행·일차를 그대로 쓴다 — "같은 동네에서 다른 가게"가 보장된다.
	 * 제외 = 이 코스·그 상권 행·그 일차에서 앞서 보여준 가게 전부(프로필 버전 무관) ∪ 코스에 담긴 가게. 이전 추천에 RETRY 를 남긴다 —
	 * FE 는 따로 반응을 보내지 않는다. 요청 areaId 가 이전 추천의 상권 행과 다르면 앱과 서버가 다른 동네를 말하는 것이라 400.
	 */
	private RecommendationResponse retry(Long memberId, Course course, RecommendationRequest request, int storeCount,
			String sessionId) {
		Recommendation previous = recommendationRepository
			.findByIdAndMemberId(request.previousRecommendationId(), memberId)
			.filter(found -> found.getCourseId().equals(course.getId()))
			.orElseThrow(() -> new RecommendationException(RecommendationErrorCode.RECOMMENDATION_NOT_FOUND));
		if (previous.isAnchor() || !previous.getCourseAreaId().equals(request.areaId())) {
			throw new RecommendationException(RecommendationErrorCode.PREVIOUS_AREA_MISMATCH);
		}
		CourseArea area = courseAreaRepository.findByIdAndIsActiveTrue(previous.getCourseAreaId())
			.orElseThrow(() -> new RecommendationException(RecommendationErrorCode.COURSE_AREA_NOT_FOUND));
		short day = previous.getDay();
		List<CourseStore> activeStores = courseStoreRepository
			.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(course.getId());
		UserTasteProfile profile = userTasteProfileRepository.findById(memberId).orElse(null);
		int profileVersion = profile != null ? profile.getVersion() : Recommendation.NO_PROFILE_VERSION;

		Set<Long> excludeStoreIds = new HashSet<>(storeIdsOf(activeStores));
		excludeStoreIds.addAll(shownStoreIdsOf(memberId, course.getId(), area.getId(), day));
		recommendationFeedbackRepository.save(RecommendationFeedback.of(previous.getId(), null, FeedbackAction.RETRY));
		return buildAreaRecommendation(memberId, sessionId, course, area, day, profile, profileVersion, activeStores,
			excludeStoreIds, storeCount);
	}

	/**
	 * 도장 칸 아래 동네가 여럿이면 서버가 고른다(#736) — 칩은 도장 칸 하나라 사용자가 동네를 고르지 않는다.
	 * ① 추천받는 일차에 담긴 가게가 반경 안인 동네 ② 그 일차가 비어 있으면 코스 전체에서 담긴 가게가 많은 동네
	 * ③ 이 세션에서 상세를 본 가게가 있는 동네 ④ 내 저장 가게가 있는 동네 ⑤ 취향 후보(코사인 > 임계)가 기준 이상인 동네
	 * ⑥ 대표 행(display_order 첫 행). 같으면 대표 행 순. ③~⑤는 칩 순서와 같은 신호·같은 우선순위다(PR #741 리뷰) —
	 * 세션 신호로 "서귀포시" 칩이 앞에 왔으면 클릭 뒤 추천도 그 세션에서 본 중문에서 나와야 한다.
	 * 일차를 먼저 보는 이유: 1일차 중문·2일차 성산인 코스에서 2일차를 짜는 사람에겐 성산이 맞다 — 코스 전체로 세면 중문이 된다.
	 * 코스 전체로 넘어가는 건 그 일차가 정말 빈 경우뿐이다 — 일차에 가게는 있는데 어느 동네 반경에도 안 들어가면(남원읍 가게)
	 * 다른 날의 동선이 오늘을 덮지 않게 ③으로 간다(PR #737 리뷰).
	 * 요청이 대표 행이 아닌 동네 행이면(다음 칸 제안 등으로 동네를 집어 온 것) 그대로 쓴다. 동네가 하나뿐인 칸은 조회 한 번으로 끝난다.
	 */
	private CourseArea resolveNeighborhood(Long memberId, String sessionId, TasteQuery taste, CourseArea requested,
			List<CourseStore> activeStores, short day) {
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
		Map<Long, AreaChipStats> stats = candidateRepository.findAreaChipStats(memberId, sessionId, taste,
			neighborhoods);
		int minTasteCandidates = recommendationProperties.getScore().getMinTasteCandidates();
		return neighborhoodWithMostSignal(neighborhoods, stats, AreaChipStats::sessionViewCount, 1)
			.or(() -> neighborhoodWithMostSignal(neighborhoods, stats, AreaChipStats::savedStoreCount, 1))
			.or(() -> taste.hasAxis()
				? neighborhoodWithMostSignal(neighborhoods, stats, AreaChipStats::tasteCandidateCount,
					minTasteCandidates)
				: Optional.empty())
			.orElse(neighborhoods.get(0));
	}

	/** 신호가 기준 이상이면서 가장 큰 동네. 같으면 앞(대표 행) 순. 기준에 못 미치면 비어 있다. */
	private static Optional<CourseArea> neighborhoodWithMostSignal(List<CourseArea> neighborhoods,
			Map<Long, AreaChipStats> stats, ToIntFunction<AreaChipStats> signal, int minimum) {
		CourseArea best = null;
		int bestValue = minimum - 1;
		for (CourseArea neighborhood : neighborhoods) {
			AreaChipStats chip = stats.get(neighborhood.getId());
			int value = chip != null ? signal.applyAsInt(chip) : 0;
			if (value > bestValue) {
				best = neighborhood;
				bestValue = value;
			}
		}
		return Optional.ofNullable(best);
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
	 * 그 사이 내가 기록했거나 자격을 잃은 가게(프로필·빵집다움 필터, 이 배포 전 결과)가 들어 있거나(PR #739 리뷰),
	 * 요청한 가게 수가 다르면 다시 만든다. 후보가 모자라 결과가 짧은 건 다시 만들 이유가 아니라 stores.size() 가 아니라
	 * 저장해 둔 요청 수와 비교한다. 새 행이 최신이 되어 다음부터는 그것이 재사용된다.
	 */
	private static boolean isStillValid(RecommendationResult stored, Set<Long> courseStoreIds, Set<Long> unavailable,
			int storeCount) {
		return stored.storeCount() == storeCount
			&& stored.stores().stream().noneMatch(store -> courseStoreIds.contains(store.storeId())
				|| unavailable.contains(store.storeId()));
	}

	/** 후보 풀과 취향 폴백 여부 — 폴백이면 이유 문구가 시그니처로 바뀐다. */
	private record CandidatePool(List<CandidateStoreRow> candidates, boolean fallback) {
	}

	private CandidatePool poolFor(Long memberId, TasteQuery taste, CourseArea area, Set<Long> excludeStoreIds) {
		List<CandidateStoreRow> candidates = findAreaCandidates(memberId, taste, area, excludeStoreIds);
		// 취향 폴백(설계 §9 · #661): 풀에 취향 후보(코사인 > 임계)가 3곳 미만이면 취향 항을 빼고 prior 순으로 채운다 —
		// 담백 가게가 없는 동네에서 케이크집 4곳을 "취향에 맞는 곳"이라 하지 않는다. 반경은 넓히지 않는다(09-13).
		boolean fallback = isTasteFallback(candidates, taste,
			recommendationProperties.getScore().getMinTasteCandidates());
		if (fallback) {
			candidates = findAreaCandidates(memberId, TasteQuery.empty(), area, excludeStoreIds);
		}
		return new CandidatePool(candidates, fallback);
	}

	/**
	 * 취향 폴백인가 — 풀에 취향 후보(코사인 > 임계)가 {@code minTasteCandidates} 곳 미만이면 취향 항을 빼고 다시 뽑아야 한다.
	 *
	 * <p>설정 없이 부를 수 있게 열어 둔 자리다. 이유 문장 실험(#662)이 같은 판정을 해야 하는데, 복제해 두면 임계가 한쪽에서만
	 * 바뀌어도 조용히 갈린다. 저장소를 타지 않는 순수 함수라 여기만 고치면 양쪽이 같이 따라온다.
	 */
	static boolean isTasteFallback(List<CandidateStoreRow> candidates, TasteQuery taste, int minTasteCandidates) {
		return taste.hasAxis() && !candidates.isEmpty()
			&& candidates.get(0).tasteCandidateCount() < minTasteCandidates;
	}

	private RecommendationResponse buildAreaRecommendation(Long memberId, String sessionId, Course course,
			CourseArea area, short day, UserTasteProfile profile, int profileVersion, List<CourseStore> activeStores,
			Set<Long> excludeStoreIds, int storeCount) {
		TasteQuery taste = TasteQuery.of(profile);
		CandidatePool pool = poolFor(memberId, taste, area, excludeStoreIds);
		if (pool.candidates().isEmpty()) {
			// 저장할 게 없다 — 재사용될 결과도 없고 반응을 받을 것도 없다. 대신 다음 동네와 앵커로 넘길 가게를 알려 준다(#661)
			RecommendationAreaSummary areaSummary = new RecommendationAreaSummary(area.getId(), area.getName());
			RecommendationResult empty = new RecommendationResult(titleOf(area, profile), areaSummary, 0, false,
				storeCount, List.of(), true);
			return RecommendationResponse.of(null, empty,
				nextAreaOf(memberId, sessionId, taste, course, area, day, activeStores),
				anchorStoreIdOf(activeStores, day));
		}
		return assemble(pool, memberId, sessionId, course, area, day, profile, profileVersion, storeCount);
	}

	/** 후보 풀에서 코스를 잇고 행으로 저장한다 — 첫 묶음·다시 받기·새 세션 묶음·빈 자리 채움이 전부 여기로 온다. */
	private RecommendationResponse assemble(CandidatePool pool, Long memberId, String sessionId, Course course,
			CourseArea area, short day, UserTasteProfile profile, int profileVersion, int storeCount) {
		TasteQuery taste = TasteQuery.of(profile);
		List<CandidateStoreRow> candidates = pool.candidates();
		boolean fallback = pool.fallback();
		RecommendationAreaSummary areaSummary = new RecommendationAreaSummary(area.getId(), area.getName());
		List<CandidateStoreRow> ordered = chainWithinHop(candidates, area.getMaxHopM(), storeCount);
		// 마지막 묶음 = 뽑은 후보를 이번에 다 썼고 조회 상한(15)에도 안 닿았다. 구간 상한 때문에 못 이은 후보가 남았으면 다음
		// 다시 받기에서 코스가 될 수 있으니 마지막이 아니다(PR #738 리뷰 — 랩 59칸 중 7칸이 조기 종료됐다).
		boolean lastBatch = candidates.size() < recommendationProperties.getArea().getCandidateLimit()
			&& ordered.size() == candidates.size();
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
				reasonOf(taste, row, fallback), tasteFitOf(row), distanceToNext, row.latitude(), row.longitude(),
				row.thumbnailKey()));
		}
		RecommendationResult result = new RecommendationResult(titleOf(area, profile), areaSummary, totalDistanceM,
			fallback, storeCount, stores, lastBatch);
		Recommendation saved = recommendationRepository.save(Recommendation.forArea(memberId, course.getId(),
			area.getId(), day, profileVersion, Recommendation.RULE_PROVIDER, null, null, false, write(result),
			sessionId));
		return RecommendationResponse.of(saved.getId(), result);
	}

	private List<CandidateStoreRow> findAreaCandidates(Long memberId, TasteQuery taste, CourseArea area,
			Set<Long> excludeStoreIds) {
		return candidateRepository.findAreaCandidates(memberId, taste, area.getCenterLat(), area.getCenterLng(),
			area.getRadiusM(), area.getSido(), area.getRegion2nd(), area.getRegion3rd(), excludeStoreIds,
			recommendationProperties.getArea().getCandidateLimit());
	}

	/**
	 * 이유 문구(#661 · 설계 §7.1): 취향 폴백이면 전부 시그니처, 취향 후보(코사인 > 임계)면 취향 문구, 그 밖은 시그니처.
	 * 축이 없는 사용자에겐 취향 근거가 없으니 인기순 문구(RULE_REASON)를 그대로 쓴다 — "취향에 맞는다"고 지어내지 않는다.
	 */
	private String reasonOf(TasteQuery taste, CandidateStoreRow row, boolean fallback) {
		return reasonOf(taste, row, fallback, recommendationProperties.getScore().getTasteCosineThreshold());
	}

	/**
	 * 같은 규칙을 설정 없이 부를 수 있게 열어 둔 자리 — 이유 문장 실험(#662)이 "룰이 지금 뭐라고 쓰는지"를 기준선으로
	 * 나란히 놓아야 해서. 규칙 자체는 위 문서 그대로이고 여기가 유일한 구현이다.
	 */
	static String reasonOf(TasteQuery taste, CandidateStoreRow row, boolean fallback, double tasteCosineThreshold) {
		if (!taste.hasAxis()) {
			return RecommendationMessage.RULE_REASON;
		}
		if (fallback || row.tasteFit() <= tasteCosineThreshold) {
			return RecommendationMessage.SIGNATURE_REASON;
		}
		return RecommendationMessage.TASTE_REASON;
	}

	/** 응답의 tasteFit — 코사인(−1~1)을 소수 둘째 자리로. 축이 없으면 SQL 이 0 을 준다. */
	static double tasteFitOf(CandidateStoreRow row) {
		return Math.round(row.tasteFit() * 100) / 100.0;
	}

	/**
	 * 후보가 바닥났을 때의 다음 동네(#661): 같은 도장 칸의 다른 동네 먼저(대표 순, 이 코스에서 아직 추천 안 받은 행), 그다음
	 * 같은 시·도의 다음 도장 칸 — 그 칸에서 동네 선택까지 한 구체 행. FE 는 이 areaId 를 exactArea = true 로 요청해야 한다 —
	 * 대표 행(시내)이 제안됐을 때 칩 요청으로 읽히면 동네를 다시 골라 되돌아간다.
	 */
	private NextAreaResponse nextAreaOf(Long memberId, String sessionId, TasteQuery taste, Course course,
			CourseArea exhausted, short day, List<CourseStore> activeStores) {
		Set<Long> recommendedAreaIds = new HashSet<>(
			recommendationRepository.findRecommendedAreaIds(memberId, course.getId()));
		recommendedAreaIds.add(exhausted.getId());
		Optional<CourseArea> next = courseAreaRepository
			.findAllBySidoAndPassportSpotAndIsActiveTrueOrderByDisplayOrderAscNameAsc(exhausted.getSido(),
				exhausted.getPassportSpot())
			.stream()
			.filter(neighborhood -> !recommendedAreaIds.contains(neighborhood.getId()))
			.findFirst();
		if (next.isEmpty()) {
			Map<String, List<CourseArea>> spots = new LinkedHashMap<>();
			for (CourseArea area : courseAreaRepository
				.findAllBySidoAndIsActiveTrueOrderByDisplayOrderAscNameAsc(exhausted.getSido())) {
				spots.computeIfAbsent(area.getPassportSpot(), spot -> new ArrayList<>()).add(area);
			}
			for (Map.Entry<String, List<CourseArea>> spot : spots.entrySet()) {
				if (spot.getKey().equals(exhausted.getPassportSpot())) {
					continue;
				}
				CourseArea chosen = resolveNeighborhood(memberId, sessionId, taste, spot.getValue().get(0),
					activeStores,
					day);
				if (!recommendedAreaIds.contains(chosen.getId())) {
					next = Optional.of(chosen);
					break;
				}
			}
		}
		return next.map(area -> new NextAreaResponse(area.getId(), area.getName(),
			candidateRepository.countAreaStores(memberId, area))).orElse(null);
	}

	/** 후보가 바닥났을 때 앵커 추천으로 넘길 가게 — 이 일차에 담긴 가게 중 마지막(방문 순서). 다른 일차 가게는 그 일차 기준이라 안 쓴다. */
	private static Long anchorStoreIdOf(List<CourseStore> activeStores, short day) {
		Long anchorStoreId = null;
		for (CourseStore courseStore : activeStores) {
			if (courseStore.getDay() != null && courseStore.getDay() == day) {
				anchorStoreId = courseStore.getStore().getId();
			}
		}
		return anchorStoreId;
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
		UserTasteProfile profile = userTasteProfileRepository.findById(memberId).orElse(null);
		int profileVersion = profile != null ? profile.getVersion() : Recommendation.NO_PROFILE_VERSION;

		Set<Long> courseStoreIds = storeIdsOf(activeStores);
		Recommendation recommendation = recommendationRepository
			.findLatestAnchorForReuse(memberId, courseId, anchorStoreId, day, profileVersion)
			.orElse(null);
		AnchorRecommendationResult result;
		if (recommendation != null) {
			// 순위를 만든 뒤 담긴 가게·그 사이 기록했거나 자격을 잃은 가게는 뺀다 — 순서는 서로 독립이라 목록에서 빼도 나머지는 그대로 유효하다
			AnchorRecommendationResult stored = read(recommendation, AnchorRecommendationResult.class);
			Set<Long> unavailable = candidateRepository.findUnavailableStoreIds(memberId,
				stored.stores().stream().map(AnchorRecommendedStoreResponse::storeId).toList());
			result = withoutStores(stored, courseStoreIds, unavailable);
		} else {
			result = rankNearAnchor(memberId, TasteQuery.of(profile), anchor, courseStoreIds);
			if (!result.stores().isEmpty()) {
				recommendation = recommendationRepository.save(Recommendation.forAnchor(memberId, courseId,
					anchorStoreId, day, profileVersion, write(result)));
			}
		}
		return page(recommendation != null ? recommendation.getId() : null, result, Math.max(page, 0),
			recommendationProperties.getAnchor().getPageSize());
	}

	private static AnchorRecommendationResult withoutStores(AnchorRecommendationResult stored,
			Set<Long> courseStoreIds, Set<Long> unavailable) {
		return new AnchorRecommendationResult(stored.anchor(), stored.stores().stream()
			.filter(store -> !courseStoreIds.contains(store.storeId()) && !unavailable.contains(store.storeId()))
			.toList());
	}

	/** 앵커 순위: 링 순서 → 링 안에서 점수순(#661). 배지는 종류 겹침 > 태그 일치 > 축 극 라벨 > 시그니처. */
	private AnchorRecommendationResult rankNearAnchor(Long memberId, TasteQuery taste, Store anchor,
			Set<Long> courseStoreIds) {
		RecommendationProperties.Anchor anchorRules = recommendationProperties.getAnchor();
		RecommendationProperties.Score scoreRules = recommendationProperties.getScore();
		List<CandidateStoreRow> candidates = candidateRepository.findAnchorCandidates(memberId, taste,
			anchor.getLocation().getY(), anchor.getLocation().getX(), anchorRules.getRingM(),
			anchorRules.getMaxRadiusM(), courseStoreIds, anchorRules.getRankingSize());
		List<AnchorRecommendedStoreResponse> stores = candidates.stream()
			.map(row -> new AnchorRecommendedStoreResponse(row.storeId(), row.name(), (int)Math.round(row.distanceM()),
				AnchorBadgeResolver.resolve(taste, row, scoreRules), tasteFitOf(row), row.thumbnailKey()))
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

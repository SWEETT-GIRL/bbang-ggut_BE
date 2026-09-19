package sweetgirl.croissant.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Point;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.common.util.GeoDistance;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseStore;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.course.service.CourseAccessPolicy;
import sweetgirl.croissant.course.service.CourseCommandService;
import sweetgirl.croissant.personalization.entity.TasteAxis;
import sweetgirl.croissant.personalization.entity.UserTasteProfile;
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

@ExtendWith(MockitoExtension.class)
class CourseRecommendationCommandServiceTest {

	private static final Long MEMBER_ID = 7L;
	private static final Long COURSE_ID = 10L;
	private static final Long AREA_ID = 5L;
	private static final double CENTER_LAT = 37.5446;
	private static final double CENTER_LNG = 127.0557;

	@Mock
	private CourseRepository courseRepository;
	@Mock
	private CourseStoreRepository courseStoreRepository;
	@Mock
	private CourseAccessPolicy courseAccessPolicy;
	@Mock
	private CourseCommandService courseCommandService;
	@Mock
	private CourseAreaRepository courseAreaRepository;
	@Mock
	private UserTasteProfileRepository userTasteProfileRepository;
	@Mock
	private RecommendationRepository recommendationRepository;
	@Mock
	private RecommendationFeedbackRepository recommendationFeedbackRepository;
	@Mock
	private RecommendationCandidateJdbcRepository candidateRepository;
	@Spy
	private RecommendationProperties recommendationProperties = new RecommendationProperties();
	@Spy
	private ObjectMapper objectMapper = new ObjectMapper();

	@InjectMocks
	private CourseRecommendationCommandService courseRecommendationCommandService;

	private Course course(int dayCount) {
		Course course = mock(Course.class);
		lenient().when(course.getId()).thenReturn(COURSE_ID);
		lenient().when(course.getRegion()).thenReturn("서울");
		lenient().when(course.getDayCount()).thenReturn(dayCount);
		given(courseRepository.findByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.of(course));
		return course;
	}

	private static CourseArea seongsu(String sido) {
		CourseArea area = mock(CourseArea.class);
		lenient().when(area.getId()).thenReturn(AREA_ID);
		lenient().when(area.getName()).thenReturn("성수동");
		lenient().when(area.getPassportSpot()).thenReturn("성수동");
		lenient().when(area.getSido()).thenReturn(sido);
		lenient().when(area.getCenterLat()).thenReturn(CENTER_LAT);
		lenient().when(area.getCenterLng()).thenReturn(CENTER_LNG);
		lenient().when(area.getRadiusM()).thenReturn(1200);
		lenient().when(area.getMaxHopM()).thenReturn(1500);
		lenient().when(area.covers(anyDouble(), anyDouble())).thenAnswer(invocation -> GeoDistance.metersBetween(
			CENTER_LAT, CENTER_LNG, invocation.getArgument(0), invocation.getArgument(1)) <= 1200);
		return area;
	}

	/** 중심에서 동쪽으로 index × 200m 씩 떨어진 가게. 인기 점수는 index 가 작을수록 높다. */
	private static CandidateStoreRow candidate(int index) {
		double lng = CENTER_LNG + index * 0.00226; // 약 200m
		return new CandidateStoreRow(100L + index, "빵집" + index, "성수동", CENTER_LAT, lng, null, index * 200d,
			10d - index);
	}

	/** 성수동 도장 칸의 두 번째 동네 — 대표 행(seongsu)에서 동쪽으로 4.4km. covers 는 실제 거리로 판정한다. */
	private static CourseArea westNeighborhood(Long id, String name) {
		double lat = CENTER_LAT;
		double lng = CENTER_LNG + 0.05;
		CourseArea area = mock(CourseArea.class);
		lenient().when(area.getId()).thenReturn(id);
		lenient().when(area.getName()).thenReturn(name);
		lenient().when(area.getPassportSpot()).thenReturn("성수동");
		lenient().when(area.getSido()).thenReturn("서울");
		lenient().when(area.getCenterLat()).thenReturn(lat);
		lenient().when(area.getCenterLng()).thenReturn(lng);
		lenient().when(area.getRadiusM()).thenReturn(1200);
		lenient().when(area.getMaxHopM()).thenReturn(1500);
		lenient().when(area.covers(anyDouble(), anyDouble())).thenAnswer(invocation -> GeoDistance.metersBetween(lat,
			lng, invocation.getArgument(0), invocation.getArgument(1)) <= 1200);
		return area;
	}

	private void neighborhoodsOfSeongsu(CourseArea primary, CourseArea second) {
		given(courseAreaRepository.findAllBySidoAndPassportSpotAndIsActiveTrueOrderByDisplayOrderAscNameAsc("서울",
			"성수동")).willReturn(List.of(primary, second));
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		given(candidateRepository.findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(), any(),
			any(),
			anyCollection(), anyInt())).willReturn(List.of(candidate(1), candidate(2)));
		savedWithId(90L);
	}

	private static RecommendationRequest request(Integer storeCount, Integer day) {
		return new RecommendationRequest(AREA_ID, storeCount, day);
	}

	private Recommendation savedWithId(Long id) {
		Recommendation saved = mock(Recommendation.class);
		lenient().when(saved.getCreatedAt()).thenReturn(LocalDateTime.now());
		lenient().when(saved.getId()).thenReturn(id);
		given(recommendationRepository.save(any(Recommendation.class))).willReturn(saved);
		return saved;
	}

	private static CourseStore courseStoreOf(Store store, int day) {
		CourseStore courseStore = mock(CourseStore.class);
		lenient().when(courseStore.getStore()).thenReturn(store);
		lenient().when(courseStore.getDay()).thenReturn(day);
		return courseStore;
	}

	private static Store store(Long id, double lat, double lng) {
		Store store = mock(Store.class);
		lenient().when(store.getId()).thenReturn(id);
		lenient().when(store.getName()).thenReturn("가게" + id);
		Point location = mock(Point.class);
		lenient().when(location.getY()).thenReturn(lat);
		lenient().when(location.getX()).thenReturn(lng);
		lenient().when(store.getLocation()).thenReturn(location);
		return store;
	}

	@Test
	@DisplayName("처음이면 인기순 후보 앞 4곳을 가까운 순으로 이어 저장하고, 마지막 가게의 다음 거리는 null 이다")
	void recommend_fresh_buildsOrderedCourseAndSaves() {
		course(1);
		CourseArea seoulArea = seongsu("서울");
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(seoulArea));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		given(recommendationRepository.findLatestAreaForReuse(MEMBER_ID, COURSE_ID, AREA_ID, (short)1, 0))
			.willReturn(Optional.empty());
		given(candidateRepository.findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(), any(),
			any(),
			anyCollection(), anyInt()))
			.willReturn(List.of(candidate(3), candidate(1), candidate(4), candidate(2), candidate(5)));
		savedWithId(77L);

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(null, null), null);

		assertThat(response.recommendationId()).isEqualTo(77L);
		assertThat(response.title()).isEqualTo("성수동 빵집 코스");
		assertThat(response.area().areaId()).isEqualTo(AREA_ID);
		// 순위 1위(3)에서 시작해 상한 안 최근접으로: 3 → 4(200m, 같은 200m 인 2보다 순위가 높다) → 5(200m) → 2(600m)
		assertThat(response.stores()).extracting(RecommendedStoreResponse::storeId)
			.containsExactly(103L, 104L, 105L, 102L);
		assertThat(response.stores().get(3).distanceToNextM()).isNull();
		assertThat(response.stores().get(0).distanceToNextM()).isBetween(150, 250);
		assertThat(response.totalDistanceM()).isBetween(900, 1100);
		assertThat(response.stores()).allSatisfy(store -> {
			assertThat(store.reason()).isEqualTo(RecommendationMessage.RULE_REASON);
			assertThat(store.tasteFit()).isZero();
		});
		assertThat(response.fallback()).isFalse();
		assertThat(response.lastBatch()).isFalse(); // 후보 5곳 > 요청 4곳 — 더 있다
		assertThat(response.nextArea()).isNull();

		ArgumentCaptor<Recommendation> saved = ArgumentCaptor.forClass(Recommendation.class);
		then(recommendationRepository).should().save(saved.capture());
		assertThat(saved.getValue().getMode()).isEqualTo(RecommendationMode.AREA);
		assertThat(saved.getValue().getProvider()).isEqualTo(Recommendation.RULE_PROVIDER);
		assertThat(saved.getValue().getDay()).isEqualTo((short)1);
		assertThat(saved.getValue().getProfileVersion()).isZero();
		assertThat(saved.getValue().getResult()).containsKey("stores");
	}

	@Test
	@DisplayName("재사용 키가 맞는 행이 있으면 후보를 다시 뽑지 않고 저장된 결과를 그대로 준다")
	void recommend_reusesStoredResult() {
		course(1);
		CourseArea seoulArea = seongsu("서울");
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(seoulArea));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		RecommendationResult stored = new RecommendationResult("성수동 빵집 코스",
			new RecommendationAreaSummary(AREA_ID, "성수동"), 900, false, 1,
			List.of(new RecommendedStoreResponse(101L, "빵집1", "성수동", "이유", 0.0, null, CENTER_LAT, CENTER_LNG,
				null)),
			true);
		Map<String, Object> storedMap = objectMapper.convertValue(stored, Map.class);
		// 2시간 전 묶음이라도 같은 세션 id 면 같은 자리다
		Recommendation reused = storedRecommendation(55L, storedMap, "s", LocalDateTime.now().minusHours(2));
		given(recommendationRepository.findLatestAreaForReuse(MEMBER_ID, COURSE_ID, AREA_ID, (short)1, 0))
			.willReturn(Optional.of(reused));

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(1, null), "s");

		assertThat(response.recommendationId()).isEqualTo(55L);
		assertThat(response.totalDistanceM()).isEqualTo(900);
		assertThat(response.lastBatch()).isTrue(); // 저장해 둔 값 그대로
		assertThat(response.stores()).extracting(RecommendedStoreResponse::storeId).containsExactly(101L);
		then(candidateRepository).should(never())
			.findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(), any(), any(), anyCollection(),
				anyInt());
		then(recommendationRepository).should(never()).save(any());
	}

	@Test
	@DisplayName("새로 열기: 다른 세션이면 보던 묶음 대신 안 본 가게로 새 묶음을 만든다 — 제외는 다시 받기와 같고 RETRY 는 안 남긴다")
	void recommend_newSession_buildsUnseenBatch() {
		course(1);
		CourseArea seoulArea = seongsu("서울");
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(seoulArea));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		Recommendation yesterday = storedRecommendation(55L, storedResult(List.of(101L, 102L, 103L, 104L), false),
			"old-session", LocalDateTime.now().minusHours(20));
		given(recommendationRepository.findLatestAreaForReuse(MEMBER_ID, COURSE_ID, AREA_ID, (short)1, 0))
			.willReturn(Optional.of(yesterday));
		given(recommendationRepository.findAllByMemberIdAndCourseIdAndModeAndCourseAreaIdAndDay(MEMBER_ID, COURSE_ID,
			RecommendationMode.AREA, AREA_ID, (short)1)).willReturn(List.of(yesterday));
		given(candidateRepository.findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(), any(),
			any(), anyCollection(), anyInt())).willReturn(List.of(candidate(6), candidate(7)));
		savedWithId(78L);

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(4, 1), "new-session");

		assertThat(response.recommendationId()).isEqualTo(78L);
		assertThat(response.stores()).extracting(RecommendedStoreResponse::storeId).containsExactly(106L, 107L);
		@SuppressWarnings("unchecked") ArgumentCaptor<Collection<Long>> excluded = ArgumentCaptor
			.forClass(Collection.class);
		then(candidateRepository).should().findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(),
			any(), any(), excluded.capture(), anyInt());
		assertThat(excluded.getValue()).containsExactlyInAnyOrder(101L, 102L, 103L, 104L);
		then(recommendationFeedbackRepository).should(never()).save(any());
	}

	@Test
	@DisplayName("새로 열기: 다른 세션인데 안 본 가게가 없으면 소진된 묶음을 주지 않고 첫 묶음을 다시 계산한다")
	void recommend_newSession_exhausted_restartsFromFirstBatch() {
		course(1);
		CourseArea seoulArea = seongsu("서울");
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(seoulArea));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		Recommendation exhausted = storedRecommendation(30L, storedResult(List.of(105L), true), "old-session",
			LocalDateTime.now().minusDays(1));
		given(recommendationRepository.findLatestAreaForReuse(MEMBER_ID, COURSE_ID, AREA_ID, (short)1, 0))
			.willReturn(Optional.of(exhausted));
		given(recommendationRepository.findAllByMemberIdAndCourseIdAndModeAndCourseAreaIdAndDay(MEMBER_ID, COURSE_ID,
			RecommendationMode.AREA, AREA_ID, (short)1)).willReturn(List.of(exhausted));
		// 첫 호출(안 본 가게만)은 비고, 둘째 호출(코스 가게만 제외)이 첫 묶음이다
		given(candidateRepository.findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(), any(),
			any(), anyCollection(), anyInt())).willReturn(List.of(), List.of(candidate(1), candidate(2)));
		savedWithId(36L);

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(4, 1), "new-session");

		assertThat(response.recommendationId()).isEqualTo(36L);
		assertThat(response.stores()).extracting(RecommendedStoreResponse::storeId).containsExactly(101L, 102L);
		@SuppressWarnings("unchecked") ArgumentCaptor<Collection<Long>> excluded = ArgumentCaptor
			.forClass(Collection.class);
		then(candidateRepository).should(times(2)).findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(),
			any(), any(), any(), excluded.capture(), anyInt());
		assertThat(excluded.getAllValues().get(0)).containsExactly(105L);
		assertThat(excluded.getAllValues().get(1)).isEmpty();
	}

	@Test
	@DisplayName("새로 열기: 세션 id 가 달라도 마지막 묶음이 30분 안이면 같은 자리다 — 보던 묶음 그대로")
	void recommend_withinWindow_reusesEvenIfSessionDiffers() {
		course(1);
		CourseArea seoulArea = seongsu("서울");
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(seoulArea));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		Recommendation tenMinutesAgo = storedRecommendation(55L, storedResult(List.of(101L), false), "old-session",
			LocalDateTime.now().minusMinutes(10));
		given(recommendationRepository.findLatestAreaForReuse(MEMBER_ID, COURSE_ID, AREA_ID, (short)1, 0))
			.willReturn(Optional.of(tenMinutesAgo));

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(4, 1), "new-session");

		assertThat(response.recommendationId()).isEqualTo(55L);
		then(recommendationRepository).should(never()).save(any());
	}

	@Test
	@DisplayName("새로 열기: 같은 세션 id 라도 마지막 묶음이 24시간을 넘었으면 새 자리다 — 안 본 가게로 새 묶음")
	void recommend_sameSessionButStale_buildsUnseenBatch() {
		course(1);
		CourseArea seoulArea = seongsu("서울");
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(seoulArea));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		Recommendation stale = storedRecommendation(55L, storedResult(List.of(101L), false), "s",
			LocalDateTime.now().minusHours(30));
		given(recommendationRepository.findLatestAreaForReuse(MEMBER_ID, COURSE_ID, AREA_ID, (short)1, 0))
			.willReturn(Optional.of(stale));
		given(recommendationRepository.findAllByMemberIdAndCourseIdAndModeAndCourseAreaIdAndDay(MEMBER_ID, COURSE_ID,
			RecommendationMode.AREA, AREA_ID, (short)1)).willReturn(List.of(stale));
		given(candidateRepository.findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(), any(),
			any(), anyCollection(), anyInt())).willReturn(List.of(candidate(6)));
		savedWithId(78L);

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(4, 1), "s");

		assertThat(response.recommendationId()).isEqualTo(78L);
		assertThat(response.stores()).extracting(RecommendedStoreResponse::storeId).containsExactly(106L);
	}

	@Test
	@DisplayName("새로 열기: 다른 세션이면 최신 묶음이 무효(담긴 가게 있음)여도 첫 묶음이 아니라 안 본 가게다 — 자격 확인은 건너뛴다")
	void recommend_newSession_invalidLatest_stillBuildsUnseenBatch() {
		course(1);
		CourseArea seoulArea = seongsu("서울");
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(seoulArea));
		CourseStore addedYesterday = courseStoreOf(store(106L, CENTER_LAT, CENTER_LNG), 1); // 어제 B 에서 담은 가게
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of(addedYesterday));
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		Recommendation first = storedRecommendation(17L, storedResult(List.of(101L, 102L, 103L, 104L), false), "old",
			LocalDateTime.now().minusDays(1));
		Recommendation latest = storedRecommendation(18L, storedResult(List.of(105L, 106L, 107L, 108L), false), "old",
			LocalDateTime.now().minusDays(1));
		given(recommendationRepository.findLatestAreaForReuse(MEMBER_ID, COURSE_ID, AREA_ID, (short)1, 0))
			.willReturn(Optional.of(latest));
		given(recommendationRepository.findAllByMemberIdAndCourseIdAndModeAndCourseAreaIdAndDay(MEMBER_ID, COURSE_ID,
			RecommendationMode.AREA, AREA_ID, (short)1)).willReturn(List.of(first, latest));
		given(candidateRepository.findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(), any(),
			any(), anyCollection(), anyInt())).willReturn(List.of(candidate(9)));
		savedWithId(19L);

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(4, 1), "new-session");

		// 첫 묶음 A(101~104)가 아니라 안 본 109
		assertThat(response.stores()).extracting(RecommendedStoreResponse::storeId).containsExactly(109L);
		@SuppressWarnings("unchecked") ArgumentCaptor<Collection<Long>> excluded = ArgumentCaptor
			.forClass(Collection.class);
		then(candidateRepository).should().findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(),
			any(), any(), excluded.capture(), anyInt());
		assertThat(excluded.getValue()).containsExactlyInAnyOrder(101L, 102L, 103L, 104L, 105L, 106L, 107L, 108L);
		then(candidateRepository).should(never()).findUnavailableStoreIds(any(), anyCollection());
	}

	@Test
	@DisplayName("새로 열기: 같은 자리인데 최신 묶음에서 방금 담은 가게가 있으면 첫 묶음으로 돌아가지 않고 빈 자리만 채운다 — 다른 묶음에서 본 가게만 뺀다")
	void recommend_sameSitting_invalidLatest_refillsKeepingPlace() {
		course(1);
		CourseArea seoulArea = seongsu("서울");
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(seoulArea));
		CourseStore justAdded = courseStoreOf(store(106L, CENTER_LAT, CENTER_LNG), 1); // 방금 B 에서 담은 가게
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of(justAdded));
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		Recommendation first = storedRecommendation(17L, storedResult(List.of(101L, 102L, 103L, 104L), false), "s",
			LocalDateTime.now().minusMinutes(5));
		Recommendation latest = storedRecommendation(18L, storedResult(List.of(105L, 106L, 107L, 108L), false), "s",
			LocalDateTime.now().minusMinutes(2));
		given(recommendationRepository.findLatestAreaForReuse(MEMBER_ID, COURSE_ID, AREA_ID, (short)1, 0))
			.willReturn(Optional.of(latest));
		given(recommendationRepository.findAllByMemberIdAndCourseIdAndModeAndCourseAreaIdAndDay(MEMBER_ID, COURSE_ID,
			RecommendationMode.AREA, AREA_ID, (short)1)).willReturn(List.of(first, latest));
		given(candidateRepository.findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(), any(),
			any(), anyCollection(), anyInt()))
			.willReturn(List.of(candidate(5), candidate(7), candidate(8), candidate(9)));
		savedWithId(19L);

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(4, 1), "s");

		// 보던 B 의 남은 105·107·108 에 안 본 109 를 채운다
		assertThat(response.stores()).extracting(RecommendedStoreResponse::storeId)
			.containsExactly(105L, 107L, 108L, 109L);
		@SuppressWarnings("unchecked") ArgumentCaptor<Collection<Long>> excluded = ArgumentCaptor
			.forClass(Collection.class);
		then(candidateRepository).should().findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(),
			any(), any(), excluded.capture(), anyInt());
		assertThat(excluded.getValue()).containsExactlyInAnyOrder(101L, 102L, 103L, 104L, 106L); // B 의 가게는 다시 후보
	}

	@Test
	@DisplayName("일차를 안 보내면 첫 빈 일차다 — 1일차가 찼으면 2일차")
	void recommend_defaultsToFirstEmptyDay() {
		course(2);
		CourseArea seoulArea = seongsu("서울");
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(seoulArea));
		Store filled = store(900L, CENTER_LAT, CENTER_LNG);
		CourseStore filledStop = courseStoreOf(filled, 1);
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of(filledStop));
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		given(recommendationRepository.findLatestAreaForReuse(MEMBER_ID, COURSE_ID, AREA_ID, (short)2, 0))
			.willReturn(Optional.empty());
		given(candidateRepository.findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(), any(),
			any(),
			anyCollection(), anyInt())).willReturn(List.of(candidate(1)));
		savedWithId(78L);

		courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID, request(null, null), null);

		ArgumentCaptor<Recommendation> saved = ArgumentCaptor.forClass(Recommendation.class);
		then(recommendationRepository).should().save(saved.capture());
		assertThat(saved.getValue().getDay()).isEqualTo((short)2);
	}

	@Test
	@DisplayName("코스 지역과 다른 시/도의 상권이면 404 다")
	void recommend_areaOfOtherSido_isNotFound() {
		course(1);
		CourseArea gyeonggiArea = seongsu("경기");
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(gyeonggiArea));

		assertThatThrownBy(
			() -> courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID, request(null, null), null))
			.isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(RecommendationErrorCode.COURSE_AREA_NOT_FOUND));
	}

	@Test
	@DisplayName("가게 수가 1~6 밖이면 코스를 읽기도 전에 400 이다")
	void recommend_storeCountOutOfRange() {
		assertThatThrownBy(
			() -> courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID, request(7, null), null))
			.isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(RecommendationErrorCode.STORE_COUNT_OUT_OF_RANGE));
		then(courseRepository).should(never()).findByIdAndDeletedAtIsNull(any());
	}

	@Test
	@DisplayName("동네 선택 ①: 코스에 담긴 가게가 반경 안인 동네를 고른다 — 응답 area 와 재사용 키가 그 동네다")
	void recommend_neighborhoodByCourseStores() {
		course(1);
		CourseArea primary = seongsu("서울");
		CourseArea west = westNeighborhood(31L, "성수 서쪽");
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(primary));
		Store inWest = store(500L, CENTER_LAT, CENTER_LNG + 0.05);
		CourseStore stopInWest = courseStoreOf(inWest, 1);
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of(stopInWest));
		neighborhoodsOfSeongsu(primary, west);
		given(recommendationRepository.findLatestAreaForReuse(MEMBER_ID, COURSE_ID, 31L, (short)1, 0))
			.willReturn(Optional.empty());

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(null, 1), null);

		assertThat(response.area().areaId()).isEqualTo(31L);
		assertThat(response.area().name()).isEqualTo("성수 서쪽");
		then(candidateRepository).should(never()).findAreaChipStats(any(), any(), any(), anyList());
	}

	@Test
	@DisplayName("동네 선택은 일차가 먼저 — 1일차 시내·2일차 서쪽인 코스에서 2일차를 짜면 서쪽, 1일차를 짜면 시내")
	void recommend_neighborhoodPrefersStoresOfRequestedDay() {
		course(2);
		CourseArea primary = seongsu("서울");
		CourseArea west = westNeighborhood(31L, "성수 서쪽");
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(primary));
		Store downtownStore = store(500L, CENTER_LAT, CENTER_LNG);
		Store westStore = store(501L, CENTER_LAT, CENTER_LNG + 0.05);
		CourseStore day1Downtown = courseStoreOf(downtownStore, 1);
		CourseStore day1Downtown2 = courseStoreOf(store(502L, CENTER_LAT, CENTER_LNG + 0.001), 1);
		CourseStore day2West = courseStoreOf(westStore, 2);
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of(day1Downtown, day1Downtown2, day2West));
		neighborhoodsOfSeongsu(primary, west);
		given(recommendationRepository.findLatestAreaForReuse(eq(MEMBER_ID), eq(COURSE_ID), any(), any(), eq(0)))
			.willReturn(Optional.empty());

		RecommendationResponse secondDay = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(null, 2), null);
		RecommendationResponse firstDay = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(null, 1), null);

		// 코스 전체로 세면 시내 2 > 서쪽 1 이지만, 2일차에 담긴 건 서쪽뿐이다
		assertThat(secondDay.area().areaId()).isEqualTo(31L);
		assertThat(firstDay.area().areaId()).isEqualTo(AREA_ID);
	}

	@Test
	@DisplayName("일차에 가게는 있는데 어느 동네 반경에도 안 들어가면 다른 일차로 넘어가지 않고 저장 가게 규칙으로 간다")
	void recommend_dayWithUnmatchedStores_doesNotFallBackToOtherDays() {
		course(2);
		CourseArea primary = seongsu("서울");
		CourseArea west = westNeighborhood(31L, "성수 서쪽");
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(primary));
		CourseStore day1Downtown = courseStoreOf(store(500L, CENTER_LAT, CENTER_LNG), 1);
		// 2일차 가게는 두 동네 어디에서도 17km 밖 — 신호가 아니다
		CourseStore day2FarAway = courseStoreOf(store(503L, CENTER_LAT, CENTER_LNG + 0.2), 2);
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of(day1Downtown, day2FarAway));
		neighborhoodsOfSeongsu(primary, west);
		given(recommendationRepository.findLatestAreaForReuse(MEMBER_ID, COURSE_ID, 31L, (short)2, 0))
			.willReturn(Optional.empty());
		given(candidateRepository.findAreaChipStats(eq(MEMBER_ID), any(), any(), anyList()))
			.willReturn(Map.of(31L, new AreaChipStats(31L, 22, true)));

		RecommendationResponse secondDay = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(null, 2), null);

		// 1일차 시내 가게로 폴백하면 5(시내)가 되지만, 저장 가게가 있는 서쪽이어야 한다
		assertThat(secondDay.area().areaId()).isEqualTo(31L);
	}

	private Map<String, Object> storedResult(List<Long> storeIds, boolean lastBatch) {
		List<RecommendedStoreResponse> stores = storeIds.stream()
			.map(id -> new RecommendedStoreResponse(id, "빵집" + id, "성수동", "이유", 0.0, null, CENTER_LAT, CENTER_LNG,
				null))
			.toList();
		RecommendationResult stored = new RecommendationResult("성수동 빵집 코스",
			new RecommendationAreaSummary(AREA_ID, "성수동"), 900, false, 4, stores, lastBatch);
		return objectMapper.convertValue(stored, Map.class);
	}

	/** 저장된 상권 추천 행 — 새로 열기의 같은 자리 판정 재료(세션 id · 만든 시각)까지 갖춘 목. */
	private static Recommendation storedRecommendation(Long id, Map<String, Object> result, String sessionId,
			LocalDateTime createdAt) {
		Recommendation stored = mock(Recommendation.class);
		lenient().when(stored.getId()).thenReturn(id);
		lenient().when(stored.getResult()).thenReturn(result);
		lenient().when(stored.getSessionId()).thenReturn(sessionId);
		lenient().when(stored.getCreatedAt()).thenReturn(createdAt);
		Set<Long> shown = new HashSet<>();
		for (Object row : (List<?>)result.get("stores")) {
			shown.add(((Number)((Map<?, ?>)row).get("storeId")).longValue());
		}
		lenient().when(stored.shownStoreIds()).thenReturn(shown); // 목은 result 필드가 비어 실제 메서드를 못 부른다
		return stored;
	}

	private Recommendation previousAreaRecommendation(Long id, Long courseAreaId, short day, Set<Long> shown) {
		Recommendation previous = mock(Recommendation.class);
		lenient().when(previous.getCreatedAt()).thenReturn(LocalDateTime.now());
		lenient().when(previous.getId()).thenReturn(id);
		lenient().when(previous.getCourseId()).thenReturn(COURSE_ID);
		lenient().when(previous.getCourseAreaId()).thenReturn(courseAreaId);
		lenient().when(previous.isAnchor()).thenReturn(false);
		lenient().when(previous.getDay()).thenReturn(day);
		lenient().when(previous.shownStoreIds()).thenReturn(shown);
		return previous;
	}

	@Test
	@DisplayName("다시 받기: 이전 추천의 상권·일차를 그대로 쓰고, 앞서 보여준 가게 전부와 코스 가게를 빼며, 이전 추천에 RETRY 를 남긴다")
	void retry_usesPreviousAreaAndDay_excludesShown_recordsRetry() {
		course(2);
		CourseArea seoulArea = seongsu("서울");
		Recommendation previous = previousAreaRecommendation(77L, AREA_ID, (short)1, Set.of(101L, 102L, 103L, 104L));
		Recommendation older = previousAreaRecommendation(70L, AREA_ID, (short)1, Set.of(105L));
		given(recommendationRepository.findByIdAndMemberId(77L, MEMBER_ID)).willReturn(Optional.of(previous));
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(seoulArea));
		CourseStore inCourse = courseStoreOf(store(900L, CENTER_LAT, CENTER_LNG), 1);
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of(inCourse));
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		given(recommendationRepository.findAllByMemberIdAndCourseIdAndModeAndCourseAreaIdAndDay(MEMBER_ID, COURSE_ID,
			RecommendationMode.AREA, AREA_ID, (short)1)).willReturn(List.of(previous, older));
		given(candidateRepository.findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(), any(),
			any(),
			anyCollection(), anyInt())).willReturn(List.of(candidate(6), candidate(7)));
		savedWithId(78L);

		// 요청 일차 2 는 무시된다 — 다시 받기는 이전 추천의 일차(1)다
		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			new RecommendationRequest(AREA_ID, 4, 2, 77L), null);

		assertThat(response.recommendationId()).isEqualTo(78L);
		assertThat(response.stores()).extracting(RecommendedStoreResponse::storeId).containsExactly(106L, 107L);
		assertThat(response.lastBatch()).isTrue(); // 후보 2곳 ≤ 요청 4곳
		@SuppressWarnings("unchecked") ArgumentCaptor<Collection<Long>> excluded = ArgumentCaptor
			.forClass(Collection.class);
		then(candidateRepository).should().findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(),
			any(), any(),
			excluded.capture(), anyInt());
		assertThat(excluded.getValue()).containsExactlyInAnyOrder(101L, 102L, 103L, 104L, 105L, 900L);
		ArgumentCaptor<RecommendationFeedback> feedback = ArgumentCaptor.forClass(RecommendationFeedback.class);
		then(recommendationFeedbackRepository).should().save(feedback.capture());
		assertThat(feedback.getValue().getRecommendationId()).isEqualTo(77L);
		assertThat(feedback.getValue().getAction()).isEqualTo(FeedbackAction.RETRY);
		assertThat(feedback.getValue().getStoreId()).isNull();
		ArgumentCaptor<Recommendation> saved = ArgumentCaptor.forClass(Recommendation.class);
		then(recommendationRepository).should().save(saved.capture());
		assertThat(saved.getValue().getDay()).isEqualTo((short)1);
		// 재사용도, 동네 선택도 하지 않는다
		then(recommendationRepository).should(never()).findLatestAreaForReuse(any(), any(), any(), any(), anyInt());
		then(courseAreaRepository).should(never())
			.findAllBySidoAndPassportSpotAndIsActiveTrueOrderByDisplayOrderAscNameAsc(any(), any());
	}

	@Test
	@DisplayName("다시 받기: 남의 추천이거나 다른 코스의 추천이면 404")
	void retry_foreignOrOtherCourse_isNotFound() {
		course(1);
		given(recommendationRepository.findByIdAndMemberId(77L, MEMBER_ID)).willReturn(Optional.empty());
		assertThatThrownBy(() -> courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			new RecommendationRequest(AREA_ID, null, null, 77L), null))
			.isInstanceOf(RecommendationException.class)
			.hasMessageContaining(RecommendationErrorCode.RECOMMENDATION_NOT_FOUND.getMessage());

		Recommendation otherCourse = previousAreaRecommendation(78L, AREA_ID, (short)1, Set.of());
		given(otherCourse.getCourseId()).willReturn(COURSE_ID + 1);
		given(recommendationRepository.findByIdAndMemberId(78L, MEMBER_ID)).willReturn(Optional.of(otherCourse));
		assertThatThrownBy(() -> courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			new RecommendationRequest(AREA_ID, null, null, 78L), null))
			.isInstanceOf(RecommendationException.class)
			.hasMessageContaining(RecommendationErrorCode.RECOMMENDATION_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("다시 받기: 요청 상권이 이전 추천의 상권 행과 다르거나 이전이 앵커 추천이면 400")
	void retry_areaMismatch_isRejected() {
		course(1);
		Recommendation otherArea = previousAreaRecommendation(77L, 31L, (short)1, Set.of());
		given(recommendationRepository.findByIdAndMemberId(77L, MEMBER_ID)).willReturn(Optional.of(otherArea));
		assertThatThrownBy(() -> courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			new RecommendationRequest(AREA_ID, null, null, 77L), null))
			.isInstanceOf(RecommendationException.class)
			.hasMessageContaining(RecommendationErrorCode.PREVIOUS_AREA_MISMATCH.getMessage());

		Recommendation anchor = previousAreaRecommendation(79L, null, (short)1, Set.of());
		given(anchor.isAnchor()).willReturn(true);
		given(recommendationRepository.findByIdAndMemberId(79L, MEMBER_ID)).willReturn(Optional.of(anchor));
		assertThatThrownBy(() -> courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			new RecommendationRequest(AREA_ID, null, null, 79L), null))
			.isInstanceOf(RecommendationException.class)
			.hasMessageContaining(RecommendationErrorCode.PREVIOUS_AREA_MISMATCH.getMessage());
		then(recommendationFeedbackRepository).should(never()).save(any());
	}

	@Test
	@DisplayName("후보가 바닥나면 200 에 id 없음·빈 목록·lastBatch, 같은 도장 칸의 다른 동네를 nextArea 로, 이 일차의 마지막 가게를 anchorStoreId 로")
	void recommend_exhausted_suggestsNextNeighborhoodAndAnchor() {
		course(1);
		CourseArea primary = seongsu("서울");
		CourseArea west = westNeighborhood(31L, "성수 서쪽");
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(primary));
		CourseStore firstStop = courseStoreOf(store(900L, CENTER_LAT, CENTER_LNG), 1);
		CourseStore lastStop = courseStoreOf(store(901L, CENTER_LAT, CENTER_LNG + 0.001), 1);
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of(firstStop, lastStop));
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		given(courseAreaRepository.findAllBySidoAndPassportSpotAndIsActiveTrueOrderByDisplayOrderAscNameAsc("서울",
			"성수동")).willReturn(List.of(primary, west));
		given(recommendationRepository.findLatestAreaForReuse(MEMBER_ID, COURSE_ID, AREA_ID, (short)1, 0))
			.willReturn(Optional.empty());
		given(candidateRepository.findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(), any(),
			any(),
			anyCollection(), anyInt())).willReturn(List.of());
		given(recommendationRepository.findRecommendedAreaIds(MEMBER_ID, COURSE_ID)).willReturn(List.of(AREA_ID));
		given(candidateRepository.countAreaStores(eq(MEMBER_ID), any())).willReturn(22);

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(4, 1), null);

		assertThat(response.recommendationId()).isNull();
		assertThat(response.stores()).isEmpty();
		assertThat(response.lastBatch()).isTrue();
		assertThat(response.nextArea().areaId()).isEqualTo(31L);
		assertThat(response.nextArea().name()).isEqualTo("성수 서쪽");
		assertThat(response.nextArea().storeCount()).isEqualTo(22);
		assertThat(response.anchorStoreId()).isEqualTo(901L);
		then(recommendationRepository).should(never()).save(any());
	}

	@Test
	@DisplayName("같은 도장 칸의 동네를 다 봤으면 nextArea 는 같은 시·도의 다음 도장 칸 — 그 칸에서 동네 선택까지 한 구체 행")
	void recommend_exhausted_nextAreaFallsToNextPassportSpot() {
		course(1);
		CourseArea primary = seongsu("서울");
		CourseArea yeonnam = mock(CourseArea.class);
		lenient().when(yeonnam.getId()).thenReturn(6L);
		lenient().when(yeonnam.getName()).thenReturn("연남동");
		lenient().when(yeonnam.getPassportSpot()).thenReturn("연남동");
		lenient().when(yeonnam.getSido()).thenReturn("서울");
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(primary));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		given(courseAreaRepository.findAllBySidoAndPassportSpotAndIsActiveTrueOrderByDisplayOrderAscNameAsc("서울",
			"성수동")).willReturn(List.of(primary));
		given(courseAreaRepository.findAllBySidoAndPassportSpotAndIsActiveTrueOrderByDisplayOrderAscNameAsc("서울",
			"연남동")).willReturn(List.of(yeonnam));
		given(courseAreaRepository.findAllBySidoAndIsActiveTrueOrderByDisplayOrderAscNameAsc("서울"))
			.willReturn(List.of(primary, yeonnam));
		given(recommendationRepository.findLatestAreaForReuse(MEMBER_ID, COURSE_ID, AREA_ID, (short)1, 0))
			.willReturn(Optional.empty());
		given(candidateRepository.findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(), any(),
			any(),
			anyCollection(), anyInt())).willReturn(List.of());
		given(recommendationRepository.findRecommendedAreaIds(MEMBER_ID, COURSE_ID)).willReturn(List.of());
		given(candidateRepository.countAreaStores(eq(MEMBER_ID), any())).willReturn(0);

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(4, 1), null);

		assertThat(response.nextArea().areaId()).isEqualTo(6L);
		assertThat(response.nextArea().name()).isEqualTo("연남동");
		assertThat(response.nextArea().storeCount()).isZero();
		assertThat(response.anchorStoreId()).isNull();
	}

	@Test
	@DisplayName("lastBatch: 구간 상한 때문에 못 이은 후보가 남았으면 마지막이 아니다 — 다음 다시 받기에서 코스가 된다")
	void recommend_lastBatchFalse_whenHopCapLeavesCandidates() {
		course(1);
		CourseArea seoulArea = seongsu("서울");
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(seoulArea));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		given(recommendationRepository.findLatestAreaForReuse(MEMBER_ID, COURSE_ID, AREA_ID, (short)1, 0))
			.willReturn(Optional.empty());
		// 중심 양쪽 약 880m 에 후보 2곳 — 서로 1.76km 라 상한 1.5km 안에서 이을 수 없다
		CandidateStoreRow east = new CandidateStoreRow(201L, "동쪽", "성수동", CENTER_LAT, CENTER_LNG + 0.00995, null,
			880d, 9d);
		CandidateStoreRow westStore = new CandidateStoreRow(202L, "서쪽", "성수동", CENTER_LAT, CENTER_LNG - 0.00995,
			null, 880d, 8d);
		given(candidateRepository.findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(), any(),
			any(),
			anyCollection(), anyInt())).willReturn(List.of(east, westStore));
		savedWithId(80L);

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(4, null), null);

		assertThat(response.stores()).extracting(RecommendedStoreResponse::storeId).containsExactly(201L);
		assertThat(response.lastBatch()).isFalse();
	}

	@Test
	@DisplayName("lastBatch: 후보를 이번에 다 썼고 조회 상한에 안 닿았으면 마지막이다")
	void recommend_lastBatchTrue_whenAllCandidatesUsed() {
		course(1);
		CourseArea seoulArea = seongsu("서울");
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(seoulArea));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		given(recommendationRepository.findLatestAreaForReuse(MEMBER_ID, COURSE_ID, AREA_ID, (short)1, 0))
			.willReturn(Optional.empty());
		given(candidateRepository.findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(), any(),
			any(),
			anyCollection(), anyInt())).willReturn(List.of(candidate(1), candidate(2)));
		savedWithId(81L);

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(4, null), null);

		assertThat(response.stores()).hasSize(2);
		assertThat(response.lastBatch()).isTrue();
	}

	@Test
	@DisplayName("exactArea: nextArea 에서 온 대표 행 요청은 동네를 다시 고르지 않는다 — 신호가 서쪽을 가리켜도 시내로 간다")
	void recommend_exactArea_skipsNeighborhoodResolution() {
		course(1);
		CourseArea primary = seongsu("서울");
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(primary));
		Store inWest = store(500L, CENTER_LAT, CENTER_LNG + 0.05);
		CourseStore stopInWest = courseStoreOf(inWest, 1);
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of(stopInWest));
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		given(recommendationRepository.findLatestAreaForReuse(MEMBER_ID, COURSE_ID, AREA_ID, (short)1, 0))
			.willReturn(Optional.empty());
		given(candidateRepository.findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(), any(),
			any(),
			anyCollection(), anyInt())).willReturn(List.of(candidate(1), candidate(2)));
		savedWithId(82L);

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			new RecommendationRequest(AREA_ID, null, 1, null, true), null);

		assertThat(response.area().areaId()).isEqualTo(AREA_ID); // 담긴 가게가 서쪽에 있어도 시내
		then(courseAreaRepository).should(never())
			.findAllBySidoAndPassportSpotAndIsActiveTrueOrderByDisplayOrderAscNameAsc(any(), any());
	}

	/** 검사를 마친 프로필 — 담백·바삭 쪽. 버전 1. */
	private static UserTasteProfile quizzedProfile() {
		LocalDateTime now = LocalDateTime.of(2026, 9, 14, 23, 0);
		UserTasteProfile profile = UserTasteProfile.createEmpty(MEMBER_ID, now);
		profile.applyQuiz(List.of(Map.of("axis", "taste", "choice", "B")),
			TasteAxis.of(new BigDecimal("0.800"), new BigDecimal("0.800"), new BigDecimal("0.200"),
				new BigDecimal("0.200")),
			"PCLN", now);
		return profile;
	}

	/** 점수가 실린 후보 — tasteFit 과 풀의 취향 후보 수를 지정한다. */
	private static CandidateStoreRow scored(int index, double tasteFit, int tasteCandidateCount) {
		CandidateStoreRow base = candidate(index);
		return new CandidateStoreRow(base.storeId(), base.name(), base.region3rd(), base.latitude(), base.longitude(),
			null, base.distanceM(), base.popularityScore(), 1.0 - index * 0.1, tasteFit, tasteCandidateCount,
			0.5, 0.5, 0.5, 0.5, Map.of(), List.of());
	}

	@Test
	@DisplayName("취향 폴백: 풀의 취향 후보가 3곳 미만이면 취향 항을 빼고 다시 뽑는다 — fallback 이고 이유는 전부 시그니처")
	void recommend_tasteFallback_whenFewTasteCandidates() {
		course(1);
		CourseArea seoulArea = seongsu("서울");
		UserTasteProfile profile = quizzedProfile();
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(seoulArea));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.of(profile));
		given(recommendationRepository.findLatestAreaForReuse(MEMBER_ID, COURSE_ID, AREA_ID, (short)1,
			profile.getVersion())).willReturn(Optional.empty());
		given(candidateRepository.findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(), any(),
			any(), anyCollection(), anyInt()))
			.willReturn(List.of(scored(1, 0.9, 2), scored(2, 0.5, 2), scored(3, -0.3, 2)))
			.willReturn(List.of(scored(4, 0.0, 0), scored(5, 0.0, 0)));
		savedWithId(90L);

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(4, null), null);

		assertThat(response.fallback()).isTrue();
		// 유형(PCLN)이 있는 회원도 코스 이름에 유형 이름이 없다 — 유형 이름은 앱에서 없앴다(#760)
		assertThat(response.title()).isEqualTo("성수동 빵집 코스");
		assertThat(response.stores()).extracting(RecommendedStoreResponse::storeId).containsExactly(104L, 105L);
		assertThat(response.stores()).extracting(RecommendedStoreResponse::reason)
			.containsOnly(RecommendationMessage.SIGNATURE_REASON);
		ArgumentCaptor<TasteQuery> tastes = ArgumentCaptor.forClass(TasteQuery.class);
		then(candidateRepository).should(times(2)).findAreaCandidates(eq(MEMBER_ID), tastes.capture(), anyDouble(),
			anyDouble(), anyInt(), any(), any(), any(), anyCollection(), anyInt());
		assertThat(tastes.getAllValues().get(0).hasAxis()).isTrue();
		assertThat(tastes.getAllValues().get(1).hasAxis()).isFalse(); // 취향 항을 뺀 재조회
	}

	@Test
	@DisplayName("이유·tasteFit: 취향 후보면 취향 문구, 코사인이 낮으면 시그니처, 축 없는 사용자는 인기순 문구")
	void recommend_reasonsFollowTasteFit() {
		course(1);
		CourseArea seoulArea = seongsu("서울");
		UserTasteProfile profile = quizzedProfile();
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(seoulArea));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.of(profile));
		given(recommendationRepository.findLatestAreaForReuse(MEMBER_ID, COURSE_ID, AREA_ID, (short)1,
			profile.getVersion())).willReturn(Optional.empty());
		given(candidateRepository.findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(), any(),
			any(), anyCollection(), anyInt()))
			.willReturn(List.of(scored(1, 0.876, 3), scored(2, 0.15, 3), scored(3, 0.4, 3)));
		savedWithId(91L);

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(4, null), null);

		assertThat(response.fallback()).isFalse();
		assertThat(response.stores()).extracting(RecommendedStoreResponse::reason).containsExactly(
			RecommendationMessage.TASTE_REASON, RecommendationMessage.SIGNATURE_REASON,
			RecommendationMessage.TASTE_REASON);
		assertThat(response.stores().get(0).tasteFit()).isEqualTo(0.88);
		then(candidateRepository).should(times(1)).findAreaCandidates(any(), any(), anyDouble(), anyDouble(),
			anyInt(), any(), any(), any(), anyCollection(), anyInt());
	}

	@Test
	@DisplayName("동네 선택: 담긴 가게가 없으면 이 세션에서 본 가게가 있는 동네가 저장 가게보다 먼저 — 칩을 올린 신호가 클릭 뒤 추천에도 이어진다")
	void recommend_neighborhoodBySessionBeforeSaved() {
		course(1);
		CourseArea primary = seongsu("서울");
		CourseArea west = westNeighborhood(31L, "성수 서쪽");
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(primary));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());
		neighborhoodsOfSeongsu(primary, west);
		given(recommendationRepository.findLatestAreaForReuse(MEMBER_ID, COURSE_ID, 31L, (short)1, 0))
			.willReturn(Optional.empty());
		// 시내에는 저장 가게 3곳, 서쪽에서는 이 세션에서 1곳을 봤다 → 세션이 이긴다
		given(candidateRepository.findAreaChipStats(eq(MEMBER_ID), eq("sess-1"), any(), anyList()))
			.willReturn(Map.of(AREA_ID, new AreaChipStats(AREA_ID, 260, 3, 0, 0),
				31L, new AreaChipStats(31L, 40, 0, 1, 0)));

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(null, 1), "sess-1");

		assertThat(response.area().areaId()).isEqualTo(31L);
	}

	@Test
	@DisplayName("동네 선택: 담긴·본·저장 가게가 없으면 검사한 사용자는 취향 후보가 기준 이상으로 가장 많은 동네, 기준 미만이면 대표 행")
	void recommend_neighborhoodByTasteCandidates() {
		course(1);
		CourseArea primary = seongsu("서울");
		CourseArea west = westNeighborhood(31L, "성수 서쪽");
		UserTasteProfile profile = quizzedProfile();
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(primary));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());
		given(courseAreaRepository.findAllBySidoAndPassportSpotAndIsActiveTrueOrderByDisplayOrderAscNameAsc("서울",
			"성수동")).willReturn(List.of(primary, west));
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.of(profile));
		given(recommendationRepository.findLatestAreaForReuse(eq(MEMBER_ID), eq(COURSE_ID), any(), eq((short)1),
			eq(profile.getVersion()))).willReturn(Optional.empty());
		given(candidateRepository.findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(), any(),
			any(), anyCollection(), anyInt())).willReturn(List.of(scored(1, 0.9, 5), scored(2, 0.8, 5)));
		given(candidateRepository.findAreaChipStats(eq(MEMBER_ID), any(), any(), anyList()))
			.willReturn(Map.of(AREA_ID, new AreaChipStats(AREA_ID, 260, 0, 0, 2),
				31L, new AreaChipStats(31L, 40, 0, 0, 7)))
			.willReturn(Map.of(AREA_ID, new AreaChipStats(AREA_ID, 260, 0, 0, 2),
				31L, new AreaChipStats(31L, 40, 0, 0, 2)));
		savedWithId(95L);

		RecommendationResponse byTaste = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(null, 1), null);
		RecommendationResponse byPrimary = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(null, 1), null);

		assertThat(byTaste.area().areaId()).isEqualTo(31L); // 서쪽 7 ≥ 3 이고 시내 2 보다 크다
		assertThat(byPrimary.area().areaId()).isEqualTo(AREA_ID); // 둘 다 기준(3) 미만 → 대표 행
	}

	@Test
	@DisplayName("동네 선택 ②: 담긴 가게가 없으면 내 저장 가게가 있는 동네, ③: 그것도 없으면 대표 행")
	void recommend_neighborhoodBySavedStoreThenPrimary() {
		course(1);
		CourseArea primary = seongsu("서울");
		CourseArea west = westNeighborhood(31L, "성수 서쪽");
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(primary));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());
		neighborhoodsOfSeongsu(primary, west);
		given(recommendationRepository.findLatestAreaForReuse(eq(MEMBER_ID), eq(COURSE_ID), any(), eq((short)1),
			eq(0))).willReturn(Optional.empty());
		given(candidateRepository.findAreaChipStats(eq(MEMBER_ID), any(), any(), anyList()))
			.willReturn(Map.of(31L, new AreaChipStats(31L, 22, true)))
			.willReturn(Map.of());

		RecommendationResponse bySaved = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(null, 1), null);
		RecommendationResponse byPrimary = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(null, 1), null);

		assertThat(bySaved.area().areaId()).isEqualTo(31L);
		assertThat(byPrimary.area().areaId()).isEqualTo(AREA_ID);
	}

	@Test
	@DisplayName("요청이 대표 행이 아닌 동네 행이면 그대로 쓴다 — 다음 칸 제안으로 동네를 집어 온 경우")
	void recommend_requestedNeighborhoodIsUsedAsIs() {
		course(1);
		CourseArea primary = seongsu("서울");
		CourseArea west = westNeighborhood(31L, "성수 서쪽");
		given(courseAreaRepository.findByIdAndIsActiveTrue(31L)).willReturn(Optional.of(west));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());
		neighborhoodsOfSeongsu(primary, west);
		given(recommendationRepository.findLatestAreaForReuse(MEMBER_ID, COURSE_ID, 31L, (short)1, 0))
			.willReturn(Optional.empty());

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			new RecommendationRequest(31L, null, 1), null);

		assertThat(response.area().areaId()).isEqualTo(31L);
		then(candidateRepository).should(never()).findAreaChipStats(any(), any(), any(), anyList());
	}

	@Test
	@DisplayName("구간 상한 안에 이을 후보가 없으면 거기서 멈춘다 — 인기 상위를 먼저 확정해 먼 가게를 잇지 않는다")
	void recommend_stopsWhenNoCandidateWithinMaxHop() {
		course(1);
		CourseArea seoulArea = seongsu("서울");
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(seoulArea));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		given(recommendationRepository.findLatestAreaForReuse(MEMBER_ID, COURSE_ID, AREA_ID, (short)1, 0))
			.willReturn(Optional.empty());
		// 1위(1)와 2위(2)는 200m 사이, 3위(20)는 3.8km 밖 — 상한 1,500 이라 20 은 이을 수 없다
		given(candidateRepository.findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(), any(),
			any(),
			anyCollection(), anyInt()))
			.willReturn(List.of(candidate(1), candidate(2), candidate(20)));
		savedWithId(78L);

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(4, null), null);

		assertThat(response.stores()).extracting(RecommendedStoreResponse::storeId).containsExactly(101L, 102L);
		assertThat(response.totalDistanceM()).isBetween(150, 250);
	}

	@Test
	@DisplayName("chainWithinHop: 같은 거리면 순위가 높은 쪽, 빈 후보면 빈 목록, storeCount 만큼만")
	void chainWithinHop_rules() {
		assertThat(CourseRecommendationCommandService.chainWithinHop(List.of(), 1500, 4)).isEmpty();
		assertThat(CourseRecommendationCommandService.chainWithinHop(
			List.of(candidate(3), candidate(1), candidate(4), candidate(2), candidate(5)), 1500, 2))
			.extracting(CandidateStoreRow::storeId).containsExactly(103L, 104L);
		assertThat(CourseRecommendationCommandService.chainWithinHop(
			List.of(candidate(1), candidate(2), candidate(3)), 100, 3))
			.extracting(CandidateStoreRow::storeId).containsExactly(101L);
	}

	@Test
	@DisplayName("후보가 없으면 빈 결과를 주고 행은 만들지 않는다 — 재사용될 것도 반응 받을 것도 없다")
	void recommend_noCandidates_returnsEmptyWithoutSaving() {
		course(1);
		CourseArea seoulArea = seongsu("서울");
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(seoulArea));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		given(recommendationRepository.findLatestAreaForReuse(MEMBER_ID, COURSE_ID, AREA_ID, (short)1, 0))
			.willReturn(Optional.empty());
		given(candidateRepository.findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(), any(),
			any(),
			anyCollection(), anyInt())).willReturn(List.of());

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(null, null), null);

		assertThat(response.recommendationId()).isNull();
		assertThat(response.stores()).isEmpty();
		assertThat(response.fallback()).isFalse();
		then(recommendationRepository).should(never()).save(any());
	}

	@Test
	@DisplayName("앵커: 처음이면 순위 30곳을 앵커가 담긴 일차로 행 하나에 저장하고 첫 페이지 9곳과 hasNext 를 준다. 이유는 배지뿐")
	void anchor_fresh_savesRankingAndPages() {
		Course course = course(1);
		Store anchor = store(100L, CENTER_LAT, CENTER_LNG);
		CourseStore anchorStop = courseStoreOf(anchor, 1);
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of(anchorStop));
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		given(recommendationRepository.findLatestAnchorForReuse(MEMBER_ID, COURSE_ID, 100L, (short)1, 0))
			.willReturn(Optional.empty());
		given(candidateRepository.findAnchorCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), anyInt(),
			anyCollection(), anyInt()))
			.willReturn(
				IntStream.rangeClosed(1, 12).mapToObj(CourseRecommendationCommandServiceTest::candidate).toList());
		savedWithId(88L);

		AnchorRecommendationResponse response = courseRecommendationCommandService.recommendNearAnchor(MEMBER_ID,
			COURSE_ID, 100L, 0);

		assertThat(response.recommendationId()).isEqualTo(88L);
		assertThat(response.anchor()).isEqualTo(new AnchorStoreSummary(100L, "가게100"));
		assertThat(response.stores()).hasSize(9);
		assertThat(response.hasNext()).isTrue();
		assertThat(response.stores().get(0).distanceM()).isEqualTo(200);
		assertThat(response.stores()).allSatisfy(store -> assertThat(store.reasonTag())
			.isEqualTo(AnchorReasonTag.SIGNATURE.label()));

		ArgumentCaptor<Recommendation> saved = ArgumentCaptor.forClass(Recommendation.class);
		then(recommendationRepository).should().save(saved.capture());
		assertThat(saved.getValue().getMode()).isEqualTo(RecommendationMode.ANCHOR);
		assertThat(saved.getValue().getAnchorStoreId()).isEqualTo(100L);
		assertThat(saved.getValue().getDay()).isEqualTo((short)1);
		assertThat(saved.getValue().getProvider()).isEqualTo(Recommendation.RULE_PROVIDER);
		// 담을 수 있는 사람만 — 구경만 하는 사람의 추천 행을 만들지 않는다
		then(courseAccessPolicy).should().requireEditable(course, MEMBER_ID);
		// 스파이 getter 를 매처 사이에서 부르면 매처 기록이 깨진다 — 값을 먼저 꺼낸다
		int ringM = recommendationProperties.getAnchor().getRingM();
		int maxRadiusM = recommendationProperties.getAnchor().getMaxRadiusM();
		int rankingSize = recommendationProperties.getAnchor().getRankingSize();
		then(candidateRepository).should().findAnchorCandidates(any(), any(), anyDouble(), anyDouble(), eq(ringM),
			eq(maxRadiusM),
			anyCollection(), eq(rankingSize));
	}

	@Test
	@DisplayName("앵커: 저장된 순위가 있으면 그 행을 페이지로 자른다 — 마지막 페이지는 hasNext 가 false")
	void anchor_reuse_slicesStoredRanking() {
		course(1);
		Store anchor = store(100L, CENTER_LAT, CENTER_LNG);
		CourseStore anchorStop = courseStoreOf(anchor, 1);
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of(anchorStop));
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		AnchorRecommendationResult stored = new AnchorRecommendationResult(new AnchorStoreSummary(100L, "가게100"),
			IntStream.rangeClosed(1, 12)
				.mapToObj(i -> new AnchorRecommendedStoreResponse(100L + i, "빵집" + i, i * 200, "이 동네 시그니처", 0.0,
					null))
				.toList());
		Map<String, Object> storedMap = objectMapper.convertValue(stored, Map.class);
		Recommendation reused = mock(Recommendation.class);
		lenient().when(reused.getCreatedAt()).thenReturn(LocalDateTime.now());
		given(reused.getId()).willReturn(66L);
		given(reused.getResult()).willReturn(storedMap);
		given(recommendationRepository.findLatestAnchorForReuse(MEMBER_ID, COURSE_ID, 100L, (short)1, 0))
			.willReturn(Optional.of(reused));

		AnchorRecommendationResponse page1 = courseRecommendationCommandService.recommendNearAnchor(MEMBER_ID,
			COURSE_ID, 100L, 1);
		AnchorRecommendationResponse page5 = courseRecommendationCommandService.recommendNearAnchor(MEMBER_ID,
			COURSE_ID, 100L, 5);

		assertThat(page1.recommendationId()).isEqualTo(66L);
		assertThat(page1.stores()).extracting(AnchorRecommendedStoreResponse::storeId)
			.containsExactly(110L, 111L, 112L);
		assertThat(page1.hasNext()).isFalse();
		assertThat(page5.stores()).isEmpty();
		assertThat(page5.hasNext()).isFalse();
		then(candidateRepository).should(never()).findAnchorCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(),
			anyInt(),
			anyCollection(), anyInt());
		then(recommendationRepository).should(never()).save(any());
	}

	@Test
	@DisplayName("앵커가 이 코스에 담겨 있지 않으면 400 이다")
	void anchor_notInCourse_isRejected() {
		course(1);
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());

		assertThatThrownBy(() -> courseRecommendationCommandService.recommendNearAnchor(MEMBER_ID, COURSE_ID, 100L, 0))
			.isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(RecommendationErrorCode.ANCHOR_STORE_NOT_IN_COURSE));
	}

	private static RecommendationResult storedAreaResult(int storeCount) {
		List<RecommendedStoreResponse> stores = IntStream.rangeClosed(1, storeCount)
			.mapToObj(i -> new RecommendedStoreResponse(100L + i, "빵집" + i, "성수동", "이유", 0.0, null, CENTER_LAT,
				CENTER_LNG, null))
			.toList();
		return new RecommendationResult("성수동 빵집 코스", new RecommendationAreaSummary(AREA_ID, "성수동"), 900, false,
			storeCount, stores);
	}

	private Recommendation storedRecommendation(Long id, Object result) {
		Map<String, Object> resultMap = objectMapper.convertValue(result, Map.class);
		Recommendation stored = mock(Recommendation.class);
		lenient().when(stored.getCreatedAt()).thenReturn(LocalDateTime.now());
		lenient().when(stored.getId()).thenReturn(id);
		lenient().when(stored.getResult()).thenReturn(resultMap);
		return stored;
	}

	@Test
	@DisplayName("저장된 결과의 가게를 그 사이 기록했거나 자격을 잃었으면(프로필·빵집다움) 재사용하지 않고 다시 만든다")
	void recommend_reuse_rejectedWhenStoredStoreNowUnavailable() {
		course(1);
		CourseArea seoulArea = seongsu("서울");
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(seoulArea));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		Recommendation stale = storedRecommendation(55L, storedAreaResult(4));
		given(recommendationRepository.findLatestAreaForReuse(MEMBER_ID, COURSE_ID, AREA_ID, (short)1, 0))
			.willReturn(Optional.of(stale));
		given(candidateRepository.findUnavailableStoreIds(eq(MEMBER_ID), anyCollection())).willReturn(Set.of(103L));
		given(candidateRepository.findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(), any(),
			any(), anyCollection(), anyInt()))
			.willReturn(List.of(candidate(5), candidate(6), candidate(7), candidate(8)));
		savedWithId(83L);

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(null, null), null);

		assertThat(response.recommendationId()).isEqualTo(83L);
		assertThat(response.stores()).extracting(RecommendedStoreResponse::storeId).doesNotContain(103L);
		then(candidateRepository).should().findUnavailableStoreIds(eq(MEMBER_ID),
			argThat(ids -> ids.containsAll(List.of(101L, 102L, 103L, 104L))));
	}

	@Test
	@DisplayName("앵커 재사용: 그 사이 기록했거나 자격을 잃은 가게는 저장된 순위에서 뺀다 — 순서는 독립이라 나머지는 그대로")
	void anchor_reuse_dropsUnavailableStores() {
		course(1);
		Store anchor = store(100L, CENTER_LAT, CENTER_LNG);
		CourseStore anchorStop = courseStoreOf(anchor, 1);
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of(anchorStop));
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		AnchorRecommendationResult stored = new AnchorRecommendationResult(new AnchorStoreSummary(100L, "가게100"),
			IntStream.rangeClosed(1, 12)
				.mapToObj(i -> new AnchorRecommendedStoreResponse(100L + i, "빵집" + i, i * 200, "이 동네 시그니처", 0.0,
					null))
				.toList());
		Recommendation reused = storedRecommendation(67L, stored);
		given(recommendationRepository.findLatestAnchorForReuse(MEMBER_ID, COURSE_ID, 100L, (short)1, 0))
			.willReturn(Optional.of(reused));
		given(candidateRepository.findUnavailableStoreIds(eq(MEMBER_ID), anyCollection()))
			.willReturn(Set.of(102L, 105L));

		AnchorRecommendationResponse page0 = courseRecommendationCommandService.recommendNearAnchor(MEMBER_ID,
			COURSE_ID, 100L, 0);

		assertThat(page0.stores()).extracting(AnchorRecommendedStoreResponse::storeId)
			.doesNotContain(102L, 105L)
			.startsWith(101L, 103L, 104L, 106L)
			.hasSize(9);
		then(recommendationRepository).should(never()).save(any());
	}

	@Test
	@DisplayName("저장된 결과의 가게가 그 사이 코스에 담겼으면 재사용하지 않고 다시 만든다 — 담으면 중복 오류가 나므로")
	void recommend_reuse_rejectedWhenStoredStoreNowInCourse() {
		course(1);
		CourseArea seoulArea = seongsu("서울");
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(seoulArea));
		Store alreadyAdded = store(101L, CENTER_LAT, CENTER_LNG);
		CourseStore addedStop = courseStoreOf(alreadyAdded, 1);
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of(addedStop));
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		Recommendation stale = storedRecommendation(55L, storedAreaResult(4));
		// 1일차가 찼으니 첫 빈 일차는 마지막 일차 1 그대로
		given(recommendationRepository.findLatestAreaForReuse(MEMBER_ID, COURSE_ID, AREA_ID, (short)1, 0))
			.willReturn(Optional.of(stale));
		given(candidateRepository.findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(), any(),
			any(),
			anyCollection(), anyInt())).willReturn(List.of(candidate(2), candidate(3), candidate(4), candidate(5)));
		savedWithId(79L);

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(null, null), null);

		assertThat(response.recommendationId()).isEqualTo(79L);
		assertThat(response.stores()).extracting(RecommendedStoreResponse::storeId).doesNotContain(101L);
		then(recommendationRepository).should().save(any());
	}

	@Test
	@DisplayName("요청한 가게 수가 저장된 결과와 다르면 다시 만든다")
	void recommend_reuse_rejectedWhenStoreCountDiffers() {
		course(1);
		CourseArea seoulArea = seongsu("서울");
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(seoulArea));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		Recommendation fourStores = storedRecommendation(55L, storedAreaResult(4));
		given(recommendationRepository.findLatestAreaForReuse(MEMBER_ID, COURSE_ID, AREA_ID, (short)1, 0))
			.willReturn(Optional.of(fourStores));
		given(candidateRepository.findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(), any(),
			any(),
			anyCollection(), anyInt())).willReturn(List.of(candidate(1), candidate(2)));
		savedWithId(80L);

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(2, null), null);

		assertThat(response.recommendationId()).isEqualTo(80L);
		assertThat(response.stores()).hasSize(2);
	}

	@Test
	@DisplayName("앵커: 저장된 순위에서 그 사이 코스에 담긴 가게는 빼고 페이지를 자른다")
	void anchor_reuse_dropsStoresNowInCourse() {
		course(1);
		Store anchor = store(100L, CENTER_LAT, CENTER_LNG);
		Store addedFromRail = store(101L, CENTER_LAT, CENTER_LNG);
		CourseStore anchorStop = courseStoreOf(anchor, 1);
		CourseStore addedStop = courseStoreOf(addedFromRail, 1);
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of(anchorStop, addedStop));
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		AnchorRecommendationResult stored = new AnchorRecommendationResult(new AnchorStoreSummary(100L, "가게100"),
			IntStream.rangeClosed(1, 12)
				.mapToObj(i -> new AnchorRecommendedStoreResponse(100L + i, "빵집" + i, i * 200, "이 동네 시그니처", 0.0,
					null))
				.toList());
		Recommendation reused = storedRecommendation(66L, stored);
		given(recommendationRepository.findLatestAnchorForReuse(MEMBER_ID, COURSE_ID, 100L, (short)1, 0))
			.willReturn(Optional.of(reused));

		AnchorRecommendationResponse page0 = courseRecommendationCommandService.recommendNearAnchor(MEMBER_ID,
			COURSE_ID, 100L, 0);

		assertThat(page0.stores()).extracting(AnchorRecommendedStoreResponse::storeId)
			.doesNotContain(101L)
			.startsWith(102L)
			.hasSize(9);
		assertThat(page0.hasNext()).isTrue();
	}

	@Test
	@DisplayName("가게가 요청보다 적은 상권도 같은 요청이면 재사용한다 — 요청 수를 저장해 두고 비교하므로 행이 쌓이지 않는다")
	void recommend_reuse_acceptedWhenAreaHasFewerStoresThanRequested() {
		course(1);
		CourseArea seoulArea = seongsu("서울");
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(seoulArea));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		RecommendationResult twoOfFour = new RecommendationResult("성수동 빵집 코스",
			new RecommendationAreaSummary(AREA_ID, "성수동"), 300, false, 4,
			storedAreaResult(2).stores());
		Recommendation stored = storedRecommendation(55L, twoOfFour);
		given(recommendationRepository.findLatestAreaForReuse(MEMBER_ID, COURSE_ID, AREA_ID, (short)1, 0))
			.willReturn(Optional.of(stored));

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(null, null), null);

		assertThat(response.recommendationId()).isEqualTo(55L);
		assertThat(response.stores()).hasSize(2);
		then(candidateRepository).should(never())
			.findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(), any(), any(), anyCollection(),
				anyInt());
		then(recommendationRepository).should(never()).save(any());
	}

	@Test
	@DisplayName("일차가 0 이거나 코스가 아는 범위(일차 수 · 날짜 · 마지막 일차+1)를 넘으면 400 이다")
	void recommend_dayOutOfRange() {
		course(1);
		CourseArea seoulArea = seongsu("서울");
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(seoulArea));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());

		assertThatThrownBy(
			() -> courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID, request(null, 0), null))
			.isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(RecommendationErrorCode.DAY_OUT_OF_RANGE));
		assertThatThrownBy(
			() -> courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID, request(null, 3), null))
			.isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(RecommendationErrorCode.DAY_OUT_OF_RANGE));
		then(recommendationRepository).should(never()).findLatestAreaForReuse(any(), any(), any(), any(), anyInt());
	}

	@Test
	@DisplayName("날짜가 이틀인 코스는 dayCount 가 1 이어도 2일차를 요청할 수 있다 — 앱은 날짜로 탭을 그린다")
	void recommend_dateSpanAllowsSecondDay() {
		Course course = course(1);
		given(course.getStartDate()).willReturn(java.time.LocalDate.of(2026, 9, 21));
		given(course.getEndDate()).willReturn(java.time.LocalDate.of(2026, 9, 22));
		CourseArea seoulArea = seongsu("서울");
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(seoulArea));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		given(recommendationRepository.findLatestAreaForReuse(MEMBER_ID, COURSE_ID, AREA_ID, (short)2, 0))
			.willReturn(Optional.empty());
		given(candidateRepository.findAreaCandidates(any(), any(), anyDouble(), anyDouble(), anyInt(), any(), any(),
			any(),
			anyCollection(), anyInt())).willReturn(List.of(candidate(1)));
		savedWithId(81L);

		courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID, request(null, 2), null);

		ArgumentCaptor<Recommendation> saved = ArgumentCaptor.forClass(Recommendation.class);
		then(recommendationRepository).should().save(saved.capture());
		assertThat(saved.getValue().getDay()).isEqualTo((short)2);
	}

	@Test
	@DisplayName("여러 곳 담기: 내 추천이고 이 코스의 것이면 확인만 하고 course 에 넘긴다")
	void addRecommendedStores_delegatesAfterOwnershipCheck() {
		Recommendation mine = mock(Recommendation.class);
		lenient().when(mine.getCreatedAt()).thenReturn(LocalDateTime.now());
		given(mine.getCourseId()).willReturn(COURSE_ID);
		given(recommendationRepository.findByIdAndMemberId(77L, MEMBER_ID)).willReturn(Optional.of(mine));
		CourseStoreBulkAddRequest request = new CourseStoreBulkAddRequest(77L,
			List.of(new sweetgirl.croissant.course.dto.request.CourseStoreBulkItem(100L, 1)));

		courseRecommendationCommandService.addRecommendedStores(MEMBER_ID, COURSE_ID, request);

		then(courseCommandService).should().addStores(MEMBER_ID, COURSE_ID, request.getStores(), 77L);
	}

	@Test
	@DisplayName("여러 곳 담기: 남의 추천이거나 다른 코스의 추천이면 404 이고 담지 않는다. 추천 없이 담는 건 그대로 넘긴다")
	void addRecommendedStores_rejectsForeignRecommendation() {
		Recommendation otherCourses = mock(Recommendation.class);
		lenient().when(otherCourses.getCreatedAt()).thenReturn(LocalDateTime.now());
		given(otherCourses.getCourseId()).willReturn(11L);
		given(recommendationRepository.findByIdAndMemberId(77L, MEMBER_ID)).willReturn(Optional.of(otherCourses));
		given(recommendationRepository.findByIdAndMemberId(78L, MEMBER_ID)).willReturn(Optional.empty());
		List<sweetgirl.croissant.course.dto.request.CourseStoreBulkItem> items = List.of(
			new sweetgirl.croissant.course.dto.request.CourseStoreBulkItem(100L, 1));

		assertThatThrownBy(() -> courseRecommendationCommandService.addRecommendedStores(MEMBER_ID, COURSE_ID,
			new CourseStoreBulkAddRequest(77L, items)))
			.isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(RecommendationErrorCode.RECOMMENDATION_NOT_FOUND));
		assertThatThrownBy(() -> courseRecommendationCommandService.addRecommendedStores(MEMBER_ID, COURSE_ID,
			new CourseStoreBulkAddRequest(78L, items)))
			.isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(RecommendationErrorCode.RECOMMENDATION_NOT_FOUND));
		then(courseCommandService).should(never()).addStores(any(), any(), any(), any());

		courseRecommendationCommandService.addRecommendedStores(MEMBER_ID, COURSE_ID,
			new CourseStoreBulkAddRequest(null, items));
		then(courseCommandService).should().addStores(MEMBER_ID, COURSE_ID, items, null);
	}

	@Test
	@DisplayName("첫 빈 일차: 앞에서부터 정류장이 없는 일차, 전부 찼으면 마지막 일차")
	void firstEmptyDay() {
		Course twoDays = mock(Course.class);
		given(twoDays.getDayCount()).willReturn(2);
		Store any = store(1L, CENTER_LAT, CENTER_LNG);

		assertThat(CourseRecommendationCommandService.firstEmptyDay(twoDays, List.of())).isEqualTo(1);
		assertThat(CourseRecommendationCommandService.firstEmptyDay(twoDays, List.of(courseStoreOf(any, 1))))
			.isEqualTo(2);
		assertThat(CourseRecommendationCommandService.firstEmptyDay(twoDays,
			List.of(courseStoreOf(any, 1), courseStoreOf(any, 2)))).isEqualTo(2);
	}
}

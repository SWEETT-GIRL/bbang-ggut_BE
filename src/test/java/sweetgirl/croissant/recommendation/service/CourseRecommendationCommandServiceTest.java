package sweetgirl.croissant.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import sweetgirl.croissant.recommendation.enums.RecommendationMode;
import sweetgirl.croissant.recommendation.exception.RecommendationException;
import sweetgirl.croissant.recommendation.repository.AreaChipStats;
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;
import sweetgirl.croissant.recommendation.repository.CourseAreaRepository;
import sweetgirl.croissant.recommendation.repository.RecommendationCandidateJdbcRepository;
import sweetgirl.croissant.recommendation.repository.RecommendationRepository;
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
		given(candidateRepository.findAreaCandidates(anyDouble(), anyDouble(), anyInt(), any(), any(), any(),
			anyCollection(), anyInt())).willReturn(List.of(candidate(1), candidate(2)));
		savedWithId(90L);
	}

	private static RecommendationRequest request(Integer storeCount, Integer day) {
		return new RecommendationRequest(AREA_ID, storeCount, day, "course_empty", List.of());
	}

	private Recommendation savedWithId(Long id) {
		Recommendation saved = mock(Recommendation.class);
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
		given(candidateRepository.findAreaCandidates(anyDouble(), anyDouble(), anyInt(), any(), any(), any(),
			anyCollection(), anyInt()))
			.willReturn(List.of(candidate(3), candidate(1), candidate(4), candidate(2), candidate(5)));
		savedWithId(77L);

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(null, null));

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
				null)));
		Map<String, Object> storedMap = objectMapper.convertValue(stored, Map.class);
		Recommendation reused = mock(Recommendation.class);
		given(reused.getId()).willReturn(55L);
		given(reused.getResult()).willReturn(storedMap);
		given(recommendationRepository.findLatestAreaForReuse(MEMBER_ID, COURSE_ID, AREA_ID, (short)1, 0))
			.willReturn(Optional.of(reused));

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(1, null));

		assertThat(response.recommendationId()).isEqualTo(55L);
		assertThat(response.totalDistanceM()).isEqualTo(900);
		assertThat(response.stores()).extracting(RecommendedStoreResponse::storeId).containsExactly(101L);
		then(candidateRepository).should(never())
			.findAreaCandidates(anyDouble(), anyDouble(), anyInt(), any(), any(), any(), anyCollection(), anyInt());
		then(recommendationRepository).should(never()).save(any());
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
		given(candidateRepository.findAreaCandidates(anyDouble(), anyDouble(), anyInt(), any(), any(), any(),
			anyCollection(), anyInt())).willReturn(List.of(candidate(1)));
		savedWithId(78L);

		courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID, request(null, null));

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
			() -> courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID, request(null, null)))
			.isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(RecommendationErrorCode.COURSE_AREA_NOT_FOUND));
	}

	@Test
	@DisplayName("가게 수가 1~6 밖이면 코스를 읽기도 전에 400 이다")
	void recommend_storeCountOutOfRange() {
		assertThatThrownBy(() -> courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID, request(7, null)))
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
			request(null, 1));

		assertThat(response.area().areaId()).isEqualTo(31L);
		assertThat(response.area().name()).isEqualTo("성수 서쪽");
		then(candidateRepository).should(never()).findAreaChipStats(any(), anyList());
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
			request(null, 2));
		RecommendationResponse firstDay = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(null, 1));

		// 코스 전체로 세면 시내 2 > 서쪽 1 이지만, 2일차에 담긴 건 서쪽뿐이다
		assertThat(secondDay.area().areaId()).isEqualTo(31L);
		assertThat(firstDay.area().areaId()).isEqualTo(AREA_ID);
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
		given(candidateRepository.findAreaChipStats(eq(MEMBER_ID), anyList()))
			.willReturn(Map.of(31L, new AreaChipStats(31L, 22, true)))
			.willReturn(Map.of());

		RecommendationResponse bySaved = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(null, 1));
		RecommendationResponse byPrimary = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(null, 1));

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
			new RecommendationRequest(31L, null, 1, "course_empty", List.of()));

		assertThat(response.area().areaId()).isEqualTo(31L);
		then(candidateRepository).should(never()).findAreaChipStats(any(), anyList());
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
		given(candidateRepository.findAreaCandidates(anyDouble(), anyDouble(), anyInt(), any(), any(), any(),
			anyCollection(), anyInt()))
			.willReturn(List.of(candidate(1), candidate(2), candidate(20)));
		savedWithId(78L);

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(4, null));

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
		given(candidateRepository.findAreaCandidates(anyDouble(), anyDouble(), anyInt(), any(), any(), any(),
			anyCollection(), anyInt())).willReturn(List.of());

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(null, null));

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
		given(candidateRepository.findAnchorCandidates(anyDouble(), anyDouble(), anyInt(), anyInt(),
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
		then(candidateRepository).should().findAnchorCandidates(anyDouble(), anyDouble(), eq(ringM), eq(maxRadiusM),
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
		then(candidateRepository).should(never()).findAnchorCandidates(anyDouble(), anyDouble(), anyInt(), anyInt(),
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
		lenient().when(stored.getId()).thenReturn(id);
		lenient().when(stored.getResult()).thenReturn(resultMap);
		return stored;
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
		given(candidateRepository.findAreaCandidates(anyDouble(), anyDouble(), anyInt(), any(), any(), any(),
			anyCollection(), anyInt())).willReturn(List.of(candidate(2), candidate(3), candidate(4), candidate(5)));
		savedWithId(79L);

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(null, null));

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
		given(candidateRepository.findAreaCandidates(anyDouble(), anyDouble(), anyInt(), any(), any(), any(),
			anyCollection(), anyInt())).willReturn(List.of(candidate(1), candidate(2)));
		savedWithId(80L);

		RecommendationResponse response = courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID,
			request(2, null));

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
			request(null, null));

		assertThat(response.recommendationId()).isEqualTo(55L);
		assertThat(response.stores()).hasSize(2);
		then(candidateRepository).should(never())
			.findAreaCandidates(anyDouble(), anyDouble(), anyInt(), any(), any(), any(), anyCollection(), anyInt());
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

		assertThatThrownBy(() -> courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID, request(null, 0)))
			.isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(RecommendationErrorCode.DAY_OUT_OF_RANGE));
		assertThatThrownBy(() -> courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID, request(null, 3)))
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
		given(candidateRepository.findAreaCandidates(anyDouble(), anyDouble(), anyInt(), any(), any(), any(),
			anyCollection(), anyInt())).willReturn(List.of(candidate(1)));
		savedWithId(81L);

		courseRecommendationCommandService.recommend(MEMBER_ID, COURSE_ID, request(null, 2));

		ArgumentCaptor<Recommendation> saved = ArgumentCaptor.forClass(Recommendation.class);
		then(recommendationRepository).should().save(saved.capture());
		assertThat(saved.getValue().getDay()).isEqualTo((short)2);
	}

	@Test
	@DisplayName("여러 곳 담기: 내 추천이고 이 코스의 것이면 확인만 하고 course 에 넘긴다")
	void addRecommendedStores_delegatesAfterOwnershipCheck() {
		Recommendation mine = mock(Recommendation.class);
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

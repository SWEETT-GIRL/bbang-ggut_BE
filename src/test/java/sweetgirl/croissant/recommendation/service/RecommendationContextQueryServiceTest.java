package sweetgirl.croissant.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.service.CourseAccessPolicy;
import sweetgirl.croissant.personalization.config.PersonalizationProperties;
import sweetgirl.croissant.personalization.entity.TasteAxis;
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
import sweetgirl.croissant.recommendation.repository.RecommendationCandidateJdbcRepository;

@ExtendWith(MockitoExtension.class)
class RecommendationContextQueryServiceTest {

	private static final Long MEMBER_ID = 7L;
	private static final Long COURSE_ID = 10L;

	@Mock
	private CourseRepository courseRepository;
	@Mock
	private CourseAccessPolicy courseAccessPolicy;
	@Mock
	private CourseAreaRepository courseAreaRepository;
	@Mock
	private UserTasteProfileRepository userTasteProfileRepository;
	@Mock
	private TasteSignalJdbcRepository tasteSignalJdbcRepository;
	@Mock
	private BreadRecordRepository breadRecordRepository;
	@Mock
	private RecommendationCandidateJdbcRepository candidateRepository;
	@Spy
	private RecommendationProperties recommendationProperties = new RecommendationProperties();
	@Spy
	private PersonalizationProperties personalizationProperties = new PersonalizationProperties();

	@InjectMocks
	private RecommendationContextQueryService recommendationContextQueryService;

	private Course courseInSeoul() {
		Course course = mock(Course.class);
		lenient().when(course.getRegion()).thenReturn("서울");
		given(courseRepository.findByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.of(course));
		return course;
	}

	private static CourseArea area(Long id, String name) {
		CourseArea area = mock(CourseArea.class);
		lenient().when(area.getId()).thenReturn(id);
		lenient().when(area.getName()).thenReturn(name);
		lenient().when(area.getPassportSpot()).thenReturn(name);
		lenient().when(area.getDisplayOrder()).thenReturn(id.intValue()); // 픽스처의 표시 순서 = id 순
		lenient().when(area.getCenterLat()).thenReturn(37.5);
		lenient().when(area.getCenterLng()).thenReturn(127.0);
		lenient().when(area.getRadiusM()).thenReturn(1200);
		return area;
	}

	private static CourseArea neighborhood(Long id, String passportSpot, String name) {
		CourseArea area = area(id, name);
		lenient().when(area.getPassportSpot()).thenReturn(passportSpot);
		return area;
	}

	private static UserTasteProfile typedProfile() {
		UserTasteProfile profile = UserTasteProfile.createEmpty(MEMBER_ID, LocalDateTime.of(2026, 9, 1, 4, 0));
		BigDecimal one = new BigDecimal("1.000");
		profile.applyQuiz(List.of(Map.of("axis", "taste", "choice", "PLAIN")), (short)2,
			TasteAxis.of(one, one, one, one), null, "PCLN",
			LocalDateTime.of(2026, 9, 13, 10, 0));
		return profile;
	}

	@Test
	@DisplayName("같은 도장 칸의 동네 행들은 칩 하나로 묶인다 — id 는 대표 행, 이름은 도장 칸, 가게 수는 합, 저장 가게는 하나라도 SAVED")
	void getContext_groupsNeighborhoodsIntoOnePassportChip() {
		courseInSeoul();
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.of(typedProfile()));
		CourseArea downtown = neighborhood(9L, "서귀포시", "서귀포 시내");
		CourseArea jungmun = neighborhood(10L, "서귀포시", "중문");
		CourseArea seongsu = neighborhood(11L, "성수동", "성수동");
		given(courseAreaRepository.findAllBySidoAndIsActiveTrueOrderByDisplayOrderAscNameAsc("서울"))
			.willReturn(List.of(downtown, jungmun, seongsu));
		given(candidateRepository.findAreaChipStats(eq(MEMBER_ID), any(), any(), anyList())).willReturn(Map.of(
			9L, new AreaChipStats(9L, 75, false),
			10L, new AreaChipStats(10L, 22, true),
			11L, new AreaChipStats(11L, 260, false)));

		RecommendationContextResponse context = recommendationContextQueryService.getContext(MEMBER_ID, COURSE_ID,
			null);

		assertThat(context.areas()).hasSize(2);
		assertThat(context.areas().get(0).areaId()).isEqualTo(9L);
		assertThat(context.areas().get(0).name()).isEqualTo("서귀포시");
		assertThat(context.areas().get(0).reason()).isEqualTo(RecommendationAreaReason.SAVED);
		assertThat(context.areas().get(0).storeCount()).isEqualTo(97);
		assertThat(context.areas().get(1).name()).isEqualTo("성수동");
		assertThat(context.areas().get(1).storeCount()).isEqualTo(260);
	}

	@Test
	@DisplayName("칩 순서는 버킷 우선순위 — 세션에서 본 칸 → 저장 가게 있는 칸 → 취향 후보 충분한 칸 → 나머지 도장판 순. 자르지 않는다")
	void getContext_ordersChipsByBucket() {
		courseInSeoul();
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.of(typedProfile()));
		CourseArea seongsu = area(5L, "성수동");
		CourseArea yeonnam = area(6L, "연남동");
		CourseArea mangwon = area(7L, "망원동");
		CourseArea hannam = area(8L, "한남동");
		CourseArea gongdeok = area(9L, "공덕동");
		given(courseAreaRepository.findAllBySidoAndIsActiveTrueOrderByDisplayOrderAscNameAsc("서울"))
			.willReturn(List.of(seongsu, yeonnam, mangwon, hannam, gongdeok));
		given(candidateRepository.findAreaChipStats(eq(MEMBER_ID), any(), any(), anyList())).willReturn(Map.of(
			5L, new AreaChipStats(5L, 260, 0, 0, 40), // 취향 후보 40 — TASTE
			6L, new AreaChipStats(6L, 167, 2, 0, 3), // 저장 2 — SAVED
			7L, new AreaChipStats(7L, 162, 0, 1, 0), // 이 세션에서 1곳 봄 — SESSION(맨 앞)
			8L, new AreaChipStats(8L, 71, 0, 0, 50), // 취향 후보 50 — TASTE, 성수동보다 앞
			9L, new AreaChipStats(9L, 31, 0, 0, 1))); // 취향 후보 1 < 3 — POPULAR

		RecommendationContextResponse context = recommendationContextQueryService.getContext(MEMBER_ID, COURSE_ID,
			"session-1");

		// 신호가 없는 칸도 뒤에 남는다 — 자르면 여권에 있는 지역을 고를 수가 없다(서울은 도장 칸이 여섯이다)
		assertThat(context.areas()).extracting(RecommendationAreaResponse::areaId)
			.containsExactly(7L, 6L, 8L, 5L, 9L);
		assertThat(context.areas()).extracting(RecommendationAreaResponse::reason).containsExactly(
			RecommendationAreaReason.SESSION, RecommendationAreaReason.SAVED, RecommendationAreaReason.TASTE,
			RecommendationAreaReason.TASTE, RecommendationAreaReason.POPULAR);
		then(candidateRepository).should().findAreaChipStats(eq(MEMBER_ID), eq("session-1"), any(), anyList());
	}

	@Test
	@DisplayName("한 도장 칸에 동네가 여럿이면 신호를 합친다 — 동네마다 세션 1곳이면 칩은 SESSION 이고 강도 2. 같은 버킷·같은 강도면 도장판 순서")
	void getContext_sumsNeighborhoodSignals_andTiesByDisplayOrder() {
		courseInSeoul();
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.of(typedProfile()));
		CourseArea downtown = neighborhood(9L, "서귀포시", "서귀포 시내");
		CourseArea jungmun = neighborhood(10L, "서귀포시", "중문");
		CourseArea jejuCity = neighborhood(5L, "제주시", "제주 시내");
		CourseArea aewol = neighborhood(6L, "제주시", "애월");
		given(courseAreaRepository.findAllBySidoAndIsActiveTrueOrderByDisplayOrderAscNameAsc("서울"))
			.willReturn(List.of(jejuCity, aewol, downtown, jungmun)); // 도장판 순서: 제주시(5) → 서귀포시(9)
		given(candidateRepository.findAreaChipStats(eq(MEMBER_ID), any(), any(), anyList())).willReturn(Map.of(
			5L, new AreaChipStats(5L, 160, 0, 2, 0), // 제주 시내 세션 2
			6L, new AreaChipStats(6L, 24, 0, 0, 0),
			9L, new AreaChipStats(9L, 75, 0, 1, 0), // 서귀포 시내 세션 1
			10L, new AreaChipStats(10L, 22, 0, 1, 0))); // 중문 세션 1 → 서귀포시 합 2 = 제주시와 동률 → 도장판 순서

		RecommendationContextResponse context = recommendationContextQueryService.getContext(MEMBER_ID, COURSE_ID,
			"s");

		assertThat(context.areas()).extracting(RecommendationAreaResponse::name).containsExactly("제주시", "서귀포시");
		assertThat(context.areas()).extracting(RecommendationAreaResponse::reason)
			.containsOnly(RecommendationAreaReason.SESSION);
		assertThat(context.areas().get(1).storeCount()).isEqualTo(97); // 동네 합
		// 우선순위 값이 선언 순서와 무관하게 SESSION < SAVED < TASTE < POPULAR
		assertThat(RecommendationAreaReason.SESSION.getPriority())
			.isLessThan(RecommendationAreaReason.SAVED.getPriority());
		assertThat(RecommendationAreaReason.SAVED.getPriority())
			.isLessThan(RecommendationAreaReason.TASTE.getPriority());
		assertThat(RecommendationAreaReason.TASTE.getPriority())
			.isLessThan(RecommendationAreaReason.POPULAR.getPriority());
	}

	@Test
	@DisplayName("시군구 칸은 원 밖에서 본 가게도 신호다 — 동네 행 합이 0 이어도 시군구 신호로 SESSION, 강도는 합과 시군구 중 큰 값")
	void getContext_regionSignal_liftsChipViewedOutsideCircle() {
		courseInSeoul();
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.of(typedProfile()));
		CourseArea jejuCity = neighborhood(5L, "제주시", "제주 시내");
		CourseArea aewol = neighborhood(6L, "제주시", "애월");
		CourseArea pohang = area(41L, "포항시");
		given(courseAreaRepository.findAllBySidoAndIsActiveTrueOrderByDisplayOrderAscNameAsc("서울"))
			.willReturn(List.of(jejuCity, aewol, pohang));
		given(candidateRepository.findAreaChipStats(eq(MEMBER_ID), any(), any(), anyList())).willReturn(Map.of(
			5L, new AreaChipStats(5L, 160, 0, 0, 0, 1, 0), // 대표 행: 제주시 시군구에서 본 가게 1 (애월 원 안 그 가게)
			6L, new AreaChipStats(6L, 24, 0, 1, 0, 0, 0), // 애월 원 안 1 → 합 1, 시군구 1 → 큰 값 1 (합이면 2)
			41L, new AreaChipStats(41L, 38, 0, 0, 0, 2, 0))); // 포항시: 원 안 0, 시군구 2 → SESSION 강도 2

		RecommendationContextResponse context = recommendationContextQueryService.getContext(MEMBER_ID, COURSE_ID,
			"s");

		// 포항시(2) 가 제주시(1) 보다 앞 — 합으로 셌다면 제주시 2 로 동률이라 도장판 순서(제주시 5)가 먼저였을 것
		assertThat(context.areas()).extracting(RecommendationAreaResponse::name).containsExactly("포항시", "제주시");
		assertThat(context.areas()).extracting(RecommendationAreaResponse::reason)
			.containsOnly(RecommendationAreaReason.SESSION);
		assertThat(context.areas().get(0).storeCount()).isEqualTo(38); // 칩 숫자는 원 기준 그대로
	}

	@Test
	@DisplayName("축이 없는 사용자는 TASTE 버킷이 없다 — 취향 후보 수가 커도 도장판 순서로 POPULAR")
	void getContext_withoutAxis_noTasteBucket() {
		courseInSeoul();
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		given(tasteSignalJdbcRepository.countStrongSignalStores(MEMBER_ID)).willReturn(10);
		CourseArea seongsu = area(5L, "성수동");
		CourseArea yeonnam = area(6L, "연남동");
		given(courseAreaRepository.findAllBySidoAndIsActiveTrueOrderByDisplayOrderAscNameAsc("서울"))
			.willReturn(List.of(seongsu, yeonnam));
		given(candidateRepository.findAreaChipStats(eq(MEMBER_ID), any(), any(), anyList())).willReturn(Map.of(
			5L, new AreaChipStats(5L, 260, 0, 0, 0),
			6L, new AreaChipStats(6L, 167, 0, 0, 99)));

		RecommendationContextResponse context = recommendationContextQueryService.getContext(MEMBER_ID, COURSE_ID,
			null);

		assertThat(context.areas()).extracting(RecommendationAreaResponse::areaId).containsExactly(5L, 6L);
		assertThat(context.areas()).extracting(RecommendationAreaResponse::reason)
			.containsOnly(RecommendationAreaReason.POPULAR);
	}

	@Test
	@DisplayName("타입이 있으면 CHIPS 다(이름 없이 코드만으로). 칩은 도장 칸 전부가 표시 순서대로, 저장 가게가 반경 안이면 SAVED")
	void getContext_typedProfile_chipsWithLabel() {
		Course course = courseInSeoul();
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.of(typedProfile()));
		CourseArea seongsu = area(5L, "성수동");
		CourseArea yeonnam = area(6L, "연남동");
		CourseArea mangwon = area(7L, "망원동");
		CourseArea hannam = area(8L, "한남동");
		given(courseAreaRepository.findAllBySidoAndIsActiveTrueOrderByDisplayOrderAscNameAsc("서울"))
			.willReturn(List.of(seongsu, yeonnam, mangwon, hannam));
		given(candidateRepository.findAreaChipStats(eq(MEMBER_ID), any(), any(), anyList())).willReturn(Map.of(
			5L, new AreaChipStats(5L, 42, true),
			6L, new AreaChipStats(6L, 17, false),
			7L, new AreaChipStats(7L, 9, false)));

		RecommendationContextResponse context = recommendationContextQueryService.getContext(MEMBER_ID, COURSE_ID,
			null);

		assertThat(context.state()).isEqualTo(RecommendationContextState.CHIPS);
		assertThat(context.showAccuracyLink()).isFalse();
		// 통계가 없는 한남동도 남는다 — 가게 수로 거르지 않는다(운영 48칸의 최소가 26곳이라 빈 칩은 안 생긴다)
		assertThat(context.areas()).hasSize(4);
		assertThat(context.areas()).extracting(RecommendationAreaResponse::name)
			.containsExactly("성수동", "연남동", "망원동", "한남동");
		assertThat(context.areas()).extracting(RecommendationAreaResponse::reason)
			.containsExactly(RecommendationAreaReason.SAVED, RecommendationAreaReason.POPULAR,
				RecommendationAreaReason.POPULAR, RecommendationAreaReason.POPULAR);
		assertThat(context.areas()).extracting(RecommendationAreaResponse::storeCount).containsExactly(42, 17, 9, 0);
		// 칩 재료는 한 문장으로 — 칩마다 묻지 않는다
		then(candidateRepository).should().findAreaChipStats(eq(MEMBER_ID), any(), any(), anyList());
		then(courseAccessPolicy).should().requireViewable(course, MEMBER_ID, null);
		then(tasteSignalJdbcRepository).should(never()).countStrongSignalStores(any());
	}

	@Test
	@DisplayName("타입이 없고 강한 신호 가게가 3곳 미만이면 QUIZ 다")
	void getContext_fewSignals_isQuiz() {
		courseInSeoul();
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		given(tasteSignalJdbcRepository.countStrongSignalStores(MEMBER_ID)).willReturn(2);
		given(courseAreaRepository.findAllBySidoAndIsActiveTrueOrderByDisplayOrderAscNameAsc("서울"))
			.willReturn(List.of());

		RecommendationContextResponse context = recommendationContextQueryService.getContext(MEMBER_ID, COURSE_ID,
			null);

		assertThat(context.state()).isEqualTo(RecommendationContextState.QUIZ);
		assertThat(context.showAccuracyLink()).isFalse();
		assertThat(context.areas()).isEmpty();
	}

	@Test
	@DisplayName("타입은 없어도 강한 신호 가게가 3곳이면 CHIPS 이고, 기록이 없으면 정확도 링크가 뜬다")
	void getContext_enoughSignals_chipsWithAccuracyLink() {
		courseInSeoul();
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		given(tasteSignalJdbcRepository.countStrongSignalStores(MEMBER_ID)).willReturn(3);
		given(breadRecordRepository.countByMemberIdAndDeletedAtIsNull(MEMBER_ID)).willReturn(0L);
		given(courseAreaRepository.findAllBySidoAndIsActiveTrueOrderByDisplayOrderAscNameAsc("서울"))
			.willReturn(List.of());

		RecommendationContextResponse context = recommendationContextQueryService.getContext(MEMBER_ID, COURSE_ID,
			null);

		assertThat(context.state()).isEqualTo(RecommendationContextState.CHIPS);
		assertThat(context.showAccuracyLink()).isTrue();
	}

	@Test
	@DisplayName("코스에 지역이 없으면 칩이 비고 상권 표는 읽지 않는다")
	void getContext_courseWithoutRegion_noAreas() {
		Course course = mock(Course.class);
		given(course.getRegion()).willReturn(null);
		given(courseRepository.findByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.of(course));
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.of(typedProfile()));

		RecommendationContextResponse context = recommendationContextQueryService.getContext(MEMBER_ID, COURSE_ID,
			null);

		assertThat(context.areas()).isEmpty();
		then(courseAreaRepository).should(never()).findAllBySidoAndIsActiveTrueOrderByDisplayOrderAscNameAsc(any());
	}

	@Test
	@DisplayName("없는 코스면 404 다")
	void getContext_courseNotFound() {
		given(courseRepository.findByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> recommendationContextQueryService.getContext(MEMBER_ID, COURSE_ID, null))
			.isInstanceOfSatisfying(CourseException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(CourseErrorCode.COURSE_NOT_FOUND));
	}
}

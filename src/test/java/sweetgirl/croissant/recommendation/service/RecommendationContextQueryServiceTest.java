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
		lenient().when(area.getCenterLat()).thenReturn(37.5);
		lenient().when(area.getCenterLng()).thenReturn(127.0);
		lenient().when(area.getRadiusM()).thenReturn(1200);
		return area;
	}

	private static UserTasteProfile typedProfile() {
		UserTasteProfile profile = UserTasteProfile.createEmpty(MEMBER_ID, LocalDateTime.of(2026, 9, 1, 4, 0));
		BigDecimal one = new BigDecimal("1.000");
		profile.applyQuiz(List.of(Map.of("axis", "taste", "choice", "B")), TasteAxis.of(one, one, one, one), "PCLN",
			LocalDateTime.of(2026, 9, 13, 10, 0));
		return profile;
	}

	@Test
	@DisplayName("타입이 있으면 CHIPS 이고 라벨이 붙는다. 칩은 표시 순서대로 3개, 저장 가게가 반경 안이면 SAVED")
	void getContext_typedProfile_chipsWithLabel() {
		Course course = courseInSeoul();
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.of(typedProfile()));
		CourseArea seongsu = area(5L, "성수동");
		CourseArea yeonnam = area(6L, "연남동");
		CourseArea mangwon = area(7L, "망원동");
		CourseArea hannam = area(8L, "한남동");
		given(courseAreaRepository.findAllBySidoAndIsActiveTrueOrderByDisplayOrderAscNameAsc("서울"))
			.willReturn(List.of(seongsu, yeonnam, mangwon, hannam));
		given(candidateRepository.findAreaChipStats(eq(MEMBER_ID), anyList())).willReturn(Map.of(
			5L, new AreaChipStats(5L, 42, true),
			6L, new AreaChipStats(6L, 17, false),
			7L, new AreaChipStats(7L, 9, false)));

		RecommendationContextResponse context = recommendationContextQueryService.getContext(MEMBER_ID, COURSE_ID);

		assertThat(context.state()).isEqualTo(RecommendationContextState.CHIPS);
		assertThat(context.typeLabel()).isEqualTo("뉴웨이브 미니멀");
		assertThat(context.showAccuracyLink()).isFalse();
		assertThat(context.areas()).hasSize(3);
		assertThat(context.areas()).extracting(RecommendationAreaResponse::name)
			.containsExactly("성수동", "연남동", "망원동");
		assertThat(context.areas()).extracting(RecommendationAreaResponse::reason)
			.containsExactly(RecommendationAreaReason.SAVED, RecommendationAreaReason.POPULAR,
				RecommendationAreaReason.POPULAR);
		assertThat(context.areas()).extracting(RecommendationAreaResponse::storeCount).containsExactly(42, 17, 9);
		// 칩 재료는 한 문장으로 — 칩마다 묻지 않는다
		then(candidateRepository).should().findAreaChipStats(eq(MEMBER_ID), anyList());
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

		RecommendationContextResponse context = recommendationContextQueryService.getContext(MEMBER_ID, COURSE_ID);

		assertThat(context.state()).isEqualTo(RecommendationContextState.QUIZ);
		assertThat(context.showAccuracyLink()).isFalse();
		assertThat(context.typeLabel()).isNull();
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

		RecommendationContextResponse context = recommendationContextQueryService.getContext(MEMBER_ID, COURSE_ID);

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

		RecommendationContextResponse context = recommendationContextQueryService.getContext(MEMBER_ID, COURSE_ID);

		assertThat(context.areas()).isEmpty();
		then(courseAreaRepository).should(never()).findAllBySidoAndIsActiveTrueOrderByDisplayOrderAscNameAsc(any());
	}

	@Test
	@DisplayName("없는 코스면 404 다")
	void getContext_courseNotFound() {
		given(courseRepository.findByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> recommendationContextQueryService.getContext(MEMBER_ID, COURSE_ID))
			.isInstanceOfSatisfying(CourseException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(CourseErrorCode.COURSE_NOT_FOUND));
	}
}

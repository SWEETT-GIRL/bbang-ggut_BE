package sweetgirl.croissant.recommendation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.personalization.repository.UserTasteProfileRepository;
import sweetgirl.croissant.recommendation.config.RecommendationProperties;
import sweetgirl.croissant.recommendation.dto.request.RecommendationRequest;
import sweetgirl.croissant.recommendation.dto.response.RecommendationResponse;
import sweetgirl.croissant.recommendation.dto.response.RecommendationResult;
import sweetgirl.croissant.recommendation.dto.response.RecommendedStoreResponse;
import sweetgirl.croissant.recommendation.entity.CourseArea;
import sweetgirl.croissant.recommendation.entity.Recommendation;
import sweetgirl.croissant.recommendation.exception.RecommendationException;
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;
import sweetgirl.croissant.recommendation.repository.CourseAreaRepository;
import sweetgirl.croissant.recommendation.repository.PolygonAreaBindingRepository;
import sweetgirl.croissant.recommendation.repository.RecommendationCandidateJdbcRepository;
import sweetgirl.croissant.recommendation.repository.RecommendationFeedbackRepository;
import sweetgirl.croissant.recommendation.repository.RecommendationRepository;

class PolygonRecommendationCommandServiceTest {
	private final PolygonAreaBindingRepository bindings = mock(PolygonAreaBindingRepository.class);
	private final RecommendationCandidateJdbcRepository candidates = mock(RecommendationCandidateJdbcRepository.class);
	private final CourseAreaRepository areas = mock(CourseAreaRepository.class);
	private final CourseStoreRepository stops = mock(CourseStoreRepository.class);
	private final UserTasteProfileRepository profiles = mock(UserTasteProfileRepository.class);
	private final RecommendationRepository recommendations = mock(RecommendationRepository.class);
	private final RecommendationFeedbackRepository feedback = mock(RecommendationFeedbackRepository.class);
	private final RecommendationProperties properties = new RecommendationProperties();
	private final ObjectMapper mapper = new ObjectMapper();
	private final List<Recommendation> history = new ArrayList<>();
	private List<CandidateStoreRow> pool;
	private Course course;
	private PolygonRecommendationCommandService service;

	@BeforeEach
	void setup() {
		properties.getPolygon().setVersionKey("v1");
		course = mock(Course.class);
		when(course.getId()).thenReturn(10L);
		when(course.getRegion()).thenReturn("경기");
		when(course.getDayCount()).thenReturn(2);
		CourseArea area = CourseArea.create("경기", "성남시", "분당", null, 37, 127, 1200, 1500, null, null, 1);
		ReflectionTestUtils.setField(area, "id", 7L);
		when(areas.findByIdAndIsActiveTrue(7L)).thenReturn(Optional.of(area));
		when(bindings.findReadyBinding("v1", 7L)).thenReturn(Optional.of(70L));
		pool = IntStream.range(1, 13).mapToObj(i -> new CandidateStoreRow((long)i, "가게" + i, "정자동",
			37.05 + i * .0002, 127, null, 0, 1)).toList();
		when(candidates.findPolygonCandidates(anyLong(), any(), anyLong(), anyDouble(), anyDouble(),
			nullable(Integer.class), any()))
			.thenAnswer(call -> {
				Set<Long> excluded = call.getArgument(6);
				Integer radius = call.getArgument(5);
				double lat = call.getArgument(3);
				double lng = call.getArgument(4);
				return pool.stream().filter(row -> !excluded.contains(row.storeId()))
					.filter(row -> radius == null || sweetgirl.croissant.common.util.GeoDistance
						.metersBetween(lat, lng, row.latitude(), row.longitude()) <= radius)
					.toList();
			});
		when(recommendations.save(any())).thenAnswer(call -> {
			Recommendation saved = call.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", (long)history.size() + 100);
			ReflectionTestUtils.setField(saved, "createdAt", LocalDateTime.now());
			history.add(saved);
			return saved;
		});
		when(recommendations.findAllByMemberIdAndCourseIdAndModeAndDay(anyLong(), anyLong(), any(), anyShort()))
			.thenAnswer(call -> List.copyOf(history));
		when(recommendations.findByIdAndMemberId(anyLong(), eq(1L))).thenAnswer(call -> history.stream()
			.filter(row -> row.getId().equals(call.getArgument(0))).findFirst());
		when(recommendations.findLatestAreaForReuse(anyLong(), anyLong(), anyLong(), anyShort(), anyInt()))
			.thenAnswer(call -> history.stream().reduce((first, second) -> second));
		service = new PolygonRecommendationCommandService(bindings, candidates, areas, stops, profiles, recommendations,
			feedback, properties, new PolygonCoursePlanner(), new ReasonMetrics(new SimpleMeterRegistry()), mapper);
	}

	@Test
	void emptyExactNeighborhoodSuggestsOnlyAnotherNeighborhoodWithUnseenCandidates() {
		CourseArea next = CourseArea.create("경기", "성남시", "다음 동네", null, 37.05, 127, 1200, 1500, null, null, 2);
		ReflectionTestUtils.setField(next, "id", 8L);
		when(areas.findAllBySidoAndIsActiveTrueOrderByDisplayOrderAscNameAsc("경기")).thenReturn(List.of(next));
		when(bindings.findReadyBinding("v1", 8L)).thenReturn(Optional.of(80L));
		RecommendationResponse empty = recommend(null, true, "s");
		assertThat(empty.nextArea().areaId()).isEqualTo(8L);
		assertThat(empty.nextArea().storeCount()).isEqualTo(12);
		pool = List.of();
		assertThat(recommend(null, true, "s").nextArea()).isNull();
	}

	@Test
	void connectsToLastStopOfRequestedDayAndRefreshesWhenOriginMoves() {
		var store = mock(sweetgirl.croissant.store.entity.Store.class);
		when(store.getId()).thenReturn(90L);
		var factory = new org.locationtech.jts.geom.GeometryFactory();
		when(store.getLocation()).thenReturn(factory.createPoint(new org.locationtech.jts.geom.Coordinate(127, 37.05)));
		var stop = mock(sweetgirl.croissant.course.entity.CourseStore.class);
		when(stop.getDay()).thenReturn(1);
		when(stop.getStore()).thenReturn(store);
		when(stops.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(10L)).thenReturn(List.of(stop));
		recommend(null, false, "s");
		assertThat(stored(0).polygonScope().originStoreId()).isEqualTo(90L);
		when(store.getLocation())
			.thenReturn(factory.createPoint(new org.locationtech.jts.geom.Coordinate(127, 37.051)));
		recommend(null, false, "s");
		assertThat(history).hasSize(2);
		assertThat(stored(1).polygonScope().originLatitude()).isEqualTo(37.051);
		when(store.getLocation()).thenReturn(factory.createPoint(new org.locationtech.jts.geom.Coordinate(127, 38)));
		RecommendationResponse unreachable = recommend(null, false, "s");
		assertThat(unreachable.stores()).isEmpty();
		assertThat(unreachable.anchorStoreId()).isEqualTo(90L);
	}

	@Test
	void validatesDayAndKeepsPublicResponseContract() {
		assertThatThrownBy(() -> service.tryRecommend(1L, course, new RecommendationRequest(7L, 4, 9), 4, "s"))
			.isInstanceOf(RecommendationException.class);
		assertThat(mapper.convertValue(recommend(null, false, "s"), Map.class).keySet())
			.containsExactlyInAnyOrder("recommendationId", "title", "area", "totalDistanceM", "fallback", "stores",
				"lastBatch", "nextArea", "anchorStoreId");
	}

	@Test
	void findsWholeCityAndFreezesNeighborhoodAcrossMultipleRetries() {
		RecommendationResponse first = recommend(null, false, "session");
		assertThat(first.stores()).hasSize(4);
		var scope = stored(0).polygonScope();
		assertThat(scope.latitude()).isGreaterThan(37.05);
		RecommendationResponse second = recommend(first.recommendationId(), false, "session");
		RecommendationResponse third = recommend(second.recommendationId(), false, "session");
		assertThat(second.stores()).hasSize(4);
		assertThat(third.stores()).hasSize(4);
		assertThat(history.stream().flatMap(row -> row.shownStoreIds().stream())).doesNotHaveDuplicates();
		assertThat(stored(2).polygonScope()).isEqualTo(scope);
		assertThat(third.lastBatch()).isTrue();
		verify(feedback, times(2)).save(any());
	}

	@Test
	void sameSittingReusesButNewSessionExcludesPreviouslySeen() {
		RecommendationResponse first = recommend(null, false, "old");
		assertThat(recommend(null, false, "old").recommendationId()).isEqualTo(first.recommendationId());
		assertThat(history).hasSize(1);
		ReflectionTestUtils.setField(history.get(0), "createdAt", LocalDateTime.now().minusHours(2));
		RecommendationResponse next = recommend(null, false, "new");
		assertThat(next.stores()).extracting(RecommendedStoreResponse::storeId)
			.doesNotContainAnyElementsOf(history.get(0).shownStoreIds());
	}

	@Test
	void refreshesMovedOrUnavailableStoreAndKeepsRemainingVisibleStores() {
		recommend(null, false, "session");
		Set<Long> seen = history.get(0).shownStoreIds();
		Long removed = seen.iterator().next();
		pool = pool.stream().filter(row -> !row.storeId().equals(removed)).toList();
		RecommendationResponse refreshed = recommend(null, false, "session");
		seen.remove(removed);
		assertThat(refreshed.stores()).hasSize(4).extracting(RecommendedStoreResponse::storeId)
			.containsAll(seen).doesNotContain(removed);
	}

	@Test
	void exactAreaCannotDriftToWholeCity() {
		assertThat(recommend(null, true, "session").stores()).isEmpty();
		assertThat(history).isEmpty();
	}

	@Test
	void unreadyBindingFallsBackAndForeignPreviousIsRejected() {
		when(bindings.findReadyBinding("v1", 7L)).thenReturn(Optional.empty());
		assertThat(service.tryRecommend(1L, course, new RecommendationRequest(7L, 4, 1), 4, "s")).isEmpty();
		assertThatThrownBy(() -> recommend(999L, false, "s")).isInstanceOf(RecommendationException.class);
	}

	@Test
	void retryRejectsDifferentAreaAndRetainsOldBoundaryVersion() {
		RecommendationResponse first = recommend(null, false, "s");
		assertThatThrownBy(() -> service.tryRecommend(1L, course,
			new RecommendationRequest(8L, 4, 1, first.recommendationId()), 4, "s"))
			.isInstanceOf(RecommendationException.class);
		properties.getPolygon().setVersionKey("v2");
		recommend(first.recommendationId(), false, "s");
		assertThat(stored(1).polygonScope().boundaryVersion()).isEqualTo("v1");
	}

	@Test
	void reasonReplacementPreservesScopeAndLegacyJsonRemainsReadable() {
		recommend(null, false, "s");
		RecommendationResult result = stored(0);
		Map<String, Object> rewritten = mapper.convertValue(result.withStores(result.stores()), Map.class);
		assertThat(mapper.convertValue(rewritten, RecommendationResult.class).polygonScope())
			.isEqualTo(result.polygonScope());
		rewritten.remove("polygonScope");
		assertThat(mapper.convertValue(rewritten, RecommendationResult.class).polygonScope()).isNull();
	}

	private RecommendationResponse recommend(Long previous, boolean exact, String session) {
		return service.tryRecommend(1L, course, new RecommendationRequest(7L, 4, 1, previous, exact), 4, session)
			.orElseThrow();
	}

	private RecommendationResult stored(int index) {
		return mapper.convertValue(history.get(index).getResult(), RecommendationResult.class);
	}
}

package sweetgirl.croissant.recommendation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.course.dto.response.CourseStoreResponse;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.recommendation.code.RecommendationErrorCode;
import sweetgirl.croissant.recommendation.dto.request.RecommendationRequest;
import sweetgirl.croissant.recommendation.dto.response.AnchorRecommendationResponse;
import sweetgirl.croissant.recommendation.dto.response.AnchorRecommendedStoreResponse;
import sweetgirl.croissant.recommendation.dto.response.AnchorStoreSummary;
import sweetgirl.croissant.recommendation.dto.response.NextAreaResponse;
import sweetgirl.croissant.recommendation.dto.response.RecommendationAreaResponse;
import sweetgirl.croissant.recommendation.dto.response.RecommendationAreaSummary;
import sweetgirl.croissant.recommendation.dto.response.RecommendationContextResponse;
import sweetgirl.croissant.recommendation.dto.response.RecommendationResponse;
import sweetgirl.croissant.recommendation.dto.response.RecommendedStoreResponse;
import sweetgirl.croissant.recommendation.enums.RecommendationAreaReason;
import sweetgirl.croissant.recommendation.enums.RecommendationContextState;
import sweetgirl.croissant.recommendation.exception.RecommendationException;
import sweetgirl.croissant.recommendation.service.CourseRecommendationCommandService;
import sweetgirl.croissant.recommendation.service.RecommendationContextQueryService;
import sweetgirl.croissant.recommendation.service.RecommendationFeedbackCommandService;

@ExtendWith(MockitoExtension.class)
class RecommendationControllerTest {

	private MockMvc mockMvc;

	@Mock
	private RecommendationContextQueryService recommendationContextQueryService;
	@Mock
	private CourseRecommendationCommandService courseRecommendationCommandService;
	@Mock
	private RecommendationFeedbackCommandService recommendationFeedbackCommandService;

	@InjectMocks
	private RecommendationController recommendationController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(recommendationController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("컨텍스트: state·showAccuracyLink·areas[{areaId,name,reason,storeCount}]·typeLabel 이 FE 계약 그대로다")
	void getContext_returnsContractShape() throws Exception {
		given(recommendationContextQueryService.getContext(null, 10L)).willReturn(new RecommendationContextResponse(
			RecommendationContextState.CHIPS, true,
			List.of(new RecommendationAreaResponse(5L, "성수동", RecommendationAreaReason.SAVED, 42)), "뉴웨이브 미니멀"));

		mockMvc.perform(get("/courses/10/recommendation-context"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.state").value("CHIPS"))
			.andExpect(jsonPath("$.data.showAccuracyLink").value(true))
			.andExpect(jsonPath("$.data.areas[0].areaId").value(5))
			.andExpect(jsonPath("$.data.areas[0].reason").value("SAVED"))
			.andExpect(jsonPath("$.data.areas[0].storeCount").value(42))
			.andExpect(jsonPath("$.data.typeLabel").value("뉴웨이브 미니멀"));
	}

	@Test
	@DisplayName("다시 받기: previousRecommendationId 가 서비스로 가고, 바닥 응답의 lastBatch · nextArea · anchorStoreId 가 실린다")
	void recommend_retryContractShape() throws Exception {
		given(courseRecommendationCommandService.recommend(eq(null), eq(10L), any())).willReturn(
			new RecommendationResponse(null, "중문 빵집 코스", new RecommendationAreaSummary(31L, "중문"), 0, false,
				List.of(), true, new NextAreaResponse(32L, "성산", 13), 1042L));

		mockMvc.perform(post("/courses/10/recommendations").contentType(MediaType.APPLICATION_JSON)
			.content("{\"areaId\": 31, \"entryScreen\": \"course_empty\", \"sessionEvents\": [],"
				+ " \"previousRecommendationId\": 77, \"exactArea\": true}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.recommendationId").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.data.stores").isEmpty())
			.andExpect(jsonPath("$.data.lastBatch").value(true))
			.andExpect(jsonPath("$.data.nextArea.areaId").value(32))
			.andExpect(jsonPath("$.data.nextArea.name").value("성산"))
			.andExpect(jsonPath("$.data.nextArea.storeCount").value(13))
			.andExpect(jsonPath("$.data.anchorStoreId").value(1042));
		ArgumentCaptor<RecommendationRequest> request = ArgumentCaptor.forClass(RecommendationRequest.class);
		then(courseRecommendationCommandService).should().recommend(eq(null), eq(10L), request.capture());
		assertThat(request.getValue().previousRecommendationId()).isEqualTo(77L);
		assertThat(request.getValue().areaId()).isEqualTo(31L);
		assertThat(request.getValue().isExactArea()).isTrue();
	}

	@Test
	@DisplayName("상권 추천: 응답 필드가 FE 계약 그대로고 마지막 가게의 distanceToNextM 은 null 로 실린다")
	void recommend_returnsContractShape() throws Exception {
		given(courseRecommendationCommandService.recommend(eq(null), eq(10L), any())).willReturn(
			new RecommendationResponse(77L, "성수동 빵집 코스", new RecommendationAreaSummary(5L, "성수동"), 900, false,
				List.of(new RecommendedStoreResponse(101L, "빵집", "성수동", "이유", 0.0, null, 37.5, 127.0, null))));

		mockMvc.perform(post("/courses/10/recommendations").contentType(MediaType.APPLICATION_JSON)
			.content("{\"areaId\": 5, \"entryScreen\": \"course_empty\", \"sessionEvents\": []}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.recommendationId").value(77))
			.andExpect(jsonPath("$.data.area.name").value("성수동"))
			.andExpect(jsonPath("$.data.fallback").value(false))
			.andExpect(jsonPath("$.data.stores[0].storeId").value(101))
			.andExpect(jsonPath("$.data.stores[0].distanceToNextM").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.data.stores[0].tasteFit").value(0.0));
	}

	@Test
	@DisplayName("상권 추천: areaId 가 없으면 400 이다")
	void recommend_withoutAreaId_isBadRequest() throws Exception {
		mockMvc.perform(post("/courses/10/recommendations").contentType(MediaType.APPLICATION_JSON)
			.content("{\"entryScreen\": \"course_empty\"}"))
			.andExpect(status().isBadRequest());

		verify(courseRecommendationCommandService, never()).recommend(any(), any(), any());
	}

	@Test
	@DisplayName("앵커 추천: storeId·page 쿼리를 받고 page 기본값은 0 이다. 응답에 reason 문장은 없다")
	void recommendNearAnchor_returnsContractShape() throws Exception {
		given(courseRecommendationCommandService.recommendNearAnchor(null, 10L, 100L, 0)).willReturn(
			new AnchorRecommendationResponse(88L, new AnchorStoreSummary(100L, "앵커"),
				List.of(new AnchorRecommendedStoreResponse(101L, "빵집", 200, "이 동네 시그니처", 0.0, null)), true));

		mockMvc.perform(get("/courses/10/anchor-recommendations").param("storeId", "100"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.recommendationId").value(88))
			.andExpect(jsonPath("$.data.anchor.storeId").value(100))
			.andExpect(jsonPath("$.data.stores[0].reasonTag").value("이 동네 시그니처"))
			.andExpect(jsonPath("$.data.stores[0].distanceM").value(200))
			.andExpect(jsonPath("$.data.stores[0].reason").doesNotExist())
			.andExpect(jsonPath("$.data.hasNext").value(true));
	}

	@Test
	@DisplayName("여러 곳 담기: 본문 {recommendationId, stores[{storeId, day}]} 를 받아 담긴 가게 배열을 돌려준다")
	void addRecommendedStores_returnsCourseStores() throws Exception {
		CourseStoreResponse added = new CourseStoreResponse(1L, 1, 1, 100L, "빵집", 37.5, 127.0, "서울 성동구 성수동",
			null, null, false, null, null, List.of());
		given(courseRecommendationCommandService.addRecommendedStores(eq(null), eq(10L), any()))
			.willReturn(List.of(added));

		mockMvc.perform(post("/courses/10/stores/bulk").contentType(MediaType.APPLICATION_JSON)
			.content("{\"recommendationId\": 99, \"stores\": [{\"storeId\": 100, \"day\": 1}]}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].storeId").value(100))
			.andExpect(jsonPath("$.data[0].visitOrder").value(1));
	}

	@Test
	@DisplayName("여러 곳 담기: 가게 목록이 비면 400 이고 서비스는 불리지 않는다")
	void addRecommendedStores_emptyStores_isBadRequest() throws Exception {
		mockMvc.perform(post("/courses/10/stores/bulk").contentType(MediaType.APPLICATION_JSON)
			.content("{\"stores\": []}"))
			.andExpect(status().isBadRequest());

		verify(courseRecommendationCommandService, never()).addRecommendedStores(any(), any(), any());
	}

	@Test
	@DisplayName("피드백은 본문 {action, storeId, reason} 을 받아 204 로 답한다")
	void recordFeedback_returnsNoContent() throws Exception {
		mockMvc.perform(post("/recommendations/99/feedback").contentType(MediaType.APPLICATION_JSON)
			.content("{\"action\": \"DISLIKE\", \"reason\": \"TOO_FAR\"}"))
			.andExpect(status().isNoContent());

		verify(recommendationFeedbackCommandService).record(eq(null), eq(99L), any());
	}

	@Test
	@DisplayName("action 이 없으면 400 이고 서비스는 불리지 않는다")
	void recordFeedback_withoutAction_isBadRequest() throws Exception {
		mockMvc.perform(post("/recommendations/99/feedback").contentType(MediaType.APPLICATION_JSON)
			.content("{\"storeId\": 1042}"))
			.andExpect(status().isBadRequest());

		verify(recommendationFeedbackCommandService, never()).record(any(), any(), any());
	}

	@Test
	@DisplayName("남의 추천이면 404 코드가 그대로 나간다")
	void recordFeedback_unknownRecommendation_isNotFound() throws Exception {
		willThrow(new RecommendationException(RecommendationErrorCode.RECOMMENDATION_NOT_FOUND))
			.given(recommendationFeedbackCommandService).record(eq(null), eq(99L), any());

		mockMvc.perform(post("/recommendations/99/feedback").contentType(MediaType.APPLICATION_JSON)
			.content("{\"action\": \"VIEW\"}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RECOMMENDATION404_1"));
	}
}

package sweetgirl.croissant.course.draft;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.dto.response.MemoDraftResponse;
import sweetgirl.croissant.course.dto.response.MemoDraftWriteResponse;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemoDraftController")
class MemoDraftControllerTest {

	private MockMvc mockMvc;

	@Mock
	private MemoDraftCommandService memoDraftCommandService;
	@Mock
	private MemoDraftQueryService memoDraftQueryService;
	@Mock
	private ObjectProvider<MemoDraftCommandService> commandServiceProvider;
	@Mock
	private ObjectProvider<MemoDraftQueryService> queryServiceProvider;

	private MemoDraftController memoDraftController;

	@BeforeEach
	void setUp() {
		lenient().when(commandServiceProvider.getIfAvailable()).thenReturn(memoDraftCommandService);
		lenient().when(queryServiceProvider.getIfAvailable()).thenReturn(memoDraftQueryService);
		memoDraftController = new MemoDraftController(commandServiceProvider, queryServiceProvider);
		mockMvc = MockMvcBuilders
			.standaloneSetup(memoDraftController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("PUT memo-draft — 200 과 받아들여진 seq")
	void writeDraft_returns200WithAcceptedSeq() throws Exception {
		given(memoDraftCommandService.write(isNull(), eq(1L), eq(10L), any()))
			.willReturn(new MemoDraftWriteResponse(1_000L));

		mockMvc.perform(put("/courses/1/stores/10/memo-draft")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"소금빵 꼭\",\"seq\":1000}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.acceptedSeq").value(1000));
	}

	@Test
	@DisplayName("PUT memo-draft — 빈 content 도 받는다(지우기 예약)")
	void writeDraft_blankContent_accepted() throws Exception {
		given(memoDraftCommandService.write(isNull(), eq(1L), eq(10L), any()))
			.willReturn(new MemoDraftWriteResponse(1_001L));

		mockMvc.perform(put("/courses/1/stores/10/memo-draft")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"\",\"seq\":1001}"))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("PUT memo-draft — seq 가 없거나 0 이하면 400, 서비스에 닿지 않는다")
	void writeDraft_invalidSeq_returns400() throws Exception {
		mockMvc.perform(put("/courses/1/stores/10/memo-draft")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"소금빵\"}"))
			.andExpect(status().isBadRequest());
		mockMvc.perform(put("/courses/1/stores/10/memo-draft")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"소금빵\",\"seq\":0}"))
			.andExpect(status().isBadRequest());

		then(memoDraftCommandService).should(never()).write(any(), any(), any(), any());
	}

	@Test
	@DisplayName("PUT memo-draft — 201자는 400")
	void writeDraft_tooLong_returns400() throws Exception {
		mockMvc.perform(put("/courses/1/stores/10/memo-draft")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"" + "가".repeat(201) + "\",\"seq\":1}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("PUT memo-draft — 스위치 꺼짐·Redis 장애는 503 과 COURSE503_1")
	void writeDraft_unavailable_returns503() throws Exception {
		given(memoDraftCommandService.write(isNull(), eq(1L), eq(10L), any()))
			.willThrow(new CourseException(CourseErrorCode.COURSE_DRAFT_UNAVAILABLE));

		mockMvc.perform(put("/courses/1/stores/10/memo-draft")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"소금빵\",\"seq\":1}"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("COURSE503_1"));
	}

	@Test
	@DisplayName("PUT memo-draft — 초당 한도 초과는 429")
	void writeDraft_tooFrequent_returns429() throws Exception {
		given(memoDraftCommandService.write(isNull(), eq(1L), eq(10L), any()))
			.willThrow(new CourseException(CourseErrorCode.COURSE_DRAFT_TOO_FREQUENT));

		mockMvc.perform(put("/courses/1/stores/10/memo-draft")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"소금빵\",\"seq\":1}"))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("COURSE429_1"));
	}

	@Test
	@DisplayName("PUT memo-draft — 권한 없음은 403")
	void writeDraft_forbidden_returns403() throws Exception {
		given(memoDraftCommandService.write(isNull(), eq(1L), eq(10L), any()))
			.willThrow(new CourseException(CourseErrorCode.COURSE_FORBIDDEN));

		mockMvc.perform(put("/courses/1/stores/10/memo-draft")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"소금빵\",\"seq\":1}"))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("초안 Redis 가 없는 환경(서비스 빈 없음)에서는 PUT·GET 둘 다 503 — 404 가 아니다")
	void withoutDraftBeans_returns503() throws Exception {
		given(commandServiceProvider.getIfAvailable()).willReturn(null);
		given(queryServiceProvider.getIfAvailable()).willReturn(null);

		mockMvc.perform(put("/courses/1/stores/10/memo-draft")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"소금빵\",\"seq\":1}"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("COURSE503_1"));
		mockMvc.perform(get("/courses/1/drafts"))
			.andExpect(status().isServiceUnavailable());
	}

	@Test
	@DisplayName("GET drafts — 200 과 초안 목록(storeId · memberId · content · seq · isMe · updatedAt)")
	void getDrafts_returns200() throws Exception {
		given(memoDraftQueryService.getDrafts(isNull(), eq(1L))).willReturn(List.of(
			new MemoDraftResponse(10L, 7L, "같이 가요", 5L, false, LocalDateTime.of(2026, 11, 3, 18, 0))));

		mockMvc.perform(get("/courses/1/drafts"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].storeId").value(10))
			.andExpect(jsonPath("$.data[0].memberId").value(7))
			.andExpect(jsonPath("$.data[0].content").value("같이 가요"))
			.andExpect(jsonPath("$.data[0].seq").value(5))
			.andExpect(jsonPath("$.data[0].isMe").value(false))
			.andExpect(jsonPath("$.data[0].updatedAt").exists());
	}
}

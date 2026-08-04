package sweetgirl.croissant.breadlog.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.breadlog.dto.request.BreadRecordPresignedUrlRequest;
import sweetgirl.croissant.breadlog.dto.request.BreadRecordUpsertRequest;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordCreateResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordPresignedUrlInfo;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordPresignedUrlResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordResponse;
import sweetgirl.croissant.breadlog.service.BreadRecordCommandService;
import sweetgirl.croissant.breadlog.service.BreadRecordQueryService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class BreadRecordControllerTest {

	private static final String VALID_REQUEST = """
			{
			"storeId": 10,
			"storeName": "밀도 성수",
			"regionKey": "서울_성동구_성수동",
			"eatenAt": "2026-08-02T14:21:07",
			"photos": [{
			"fileName": "a.jpg",
			"contentType": "image/jpeg",
			"awsKey": "bread-records/members/7/images/a",
			"sortOrder": 0
			}],
			"tags": [{
			"photoSortOrder": 0,
			"name": "우유식빵",
			"x": 0.31,
			"y": 0.42
			}]
			}
			""";

	private MockMvc mockMvc;

	@Mock
	BreadRecordQueryService queryService;
	@Mock
	BreadRecordCommandService commandService;

	@InjectMocks
	BreadRecordController controller;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void getRecords_returns200() throws Exception {
		BreadRecordResponse response = new BreadRecordResponse(
			11L, 10L, "밀도 성수", "서울_성동구_성수동", null, null,
			LocalDateTime.of(2026, 8, 2, 14, 21), LocalDateTime.of(2026, 8, 4, 12, 0), null,
			List.of(), List.of());
		given(queryService.getRecords(isNull(), any(), any(), eq(10L))).willReturn(List.of(response));

		mockMvc.perform(get("/bread-records")
			.param("from", "2026-08-01T00:00:00")
			.param("to", "2026-09-01T00:00:00")
			.param("storeId", "10"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].id").value(11))
			.andExpect(jsonPath("$.data[0].storeName").value("밀도 성수"));
	}

	@Test
	void create_returns201() throws Exception {
		given(commandService.create(isNull(), any(BreadRecordUpsertRequest.class)))
			.willReturn(new BreadRecordCreateResponse(11L, LocalDateTime.of(2026, 8, 4, 12, 0)));

		mockMvc.perform(post("/bread-records")
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_REQUEST))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.recordId").value(11));
	}

	@Test
	void replace_returns200() throws Exception {
		willDoNothing().given(commandService).replace(eq(11L), isNull(), any(BreadRecordUpsertRequest.class));

		mockMvc.perform(patch("/bread-records/11")
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_REQUEST))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void delete_returns200() throws Exception {
		willDoNothing().given(commandService).delete(11L, null);

		mockMvc.perform(delete("/bread-records/11"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void getPresignedUrl_returns200() throws Exception {
		BreadRecordPresignedUrlInfo info = new BreadRecordPresignedUrlInfo(
			"a", "image/jpeg", "https://upload", "bread-records/members/7/images/a");
		given(commandService.generatePresignedUrls(isNull(), any(BreadRecordPresignedUrlRequest.class)))
			.willReturn(new BreadRecordPresignedUrlResponse(List.of(info)));

		mockMvc.perform(get("/bread-records/images/presigned-url")
			.param("mimeType", "image/jpeg"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.awsKey").value("bread-records/members/7/images/a"));
	}
}

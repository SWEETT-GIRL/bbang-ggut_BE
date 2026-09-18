package sweetgirl.croissant.breadlog.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.dto.request.BreadRecordPresignedUrlRequest;
import sweetgirl.croissant.breadlog.dto.request.BreadRecordUpsertRequest;
import sweetgirl.croissant.breadlog.dto.response.BreadPhotoAnalyzeResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadPhotoAnalyzeResponse.PhotoSuggestions;
import sweetgirl.croissant.breadlog.dto.response.BreadPhotoAnalyzeResponse.TagSuggestion;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordCreateResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordPresignedUrlInfo;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordPresignedUrlResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordSummaryResponse;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.service.BreadPhotoTagSuggestionService;
import sweetgirl.croissant.breadlog.service.BreadRecordCommandService;
import sweetgirl.croissant.breadlog.service.BreadRecordQueryService;
import sweetgirl.croissant.breadlog.service.BreadRecordSummaryService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.global.response.SliceResponse;

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
	@Mock
	BreadRecordSummaryService summaryService;
	@Mock
	BreadPhotoTagSuggestionService photoTagSuggestionService;

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
			11L, 10L, "밀도 성수", "서울_성동구_성수동", null, null, List.of(),
			LocalDateTime.of(2026, 8, 2, 14, 21), false, LocalDateTime.of(2026, 8, 4, 12, 0), null,
			List.of(), List.of());
		given(queryService.getRecords(isNull(), any(), any(), eq(10L), eq(0), eq(30)))
			.willReturn(new SliceResponse<>(List.of(response), false));

		mockMvc.perform(get("/bread-records")
			.param("from", "2026-08-01T00:00:00")
			.param("to", "2026-09-01T00:00:00")
			.param("storeId", "10"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content[0].id").value(11))
			.andExpect(jsonPath("$.data.content[0].storeName").value("밀도 성수"))
			.andExpect(jsonPath("$.data.content[0].isPublic").value(false))
			.andExpect(jsonPath("$.data.hasNext").value(false));
	}

	@Test
	void changeVisibility_returns200() throws Exception {
		mockMvc.perform(patch("/bread-records/11/visibility")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"isPublic\": true}"))
			.andExpect(status().isOk());

		verify(commandService).changeVisibility(eq(11L), isNull(), eq(true));
	}

	@Test
	void changeVisibility_missingIsPublic_returns400() throws Exception {
		mockMvc.perform(patch("/bread-records/11/visibility")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isBadRequest());

		verify(commandService, never()).changeVisibility(any(), any(), anyBoolean());
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
	void create_withoutPhoto_returns400() throws Exception {
		mockMvc.perform(post("/bread-records")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"storeName\":\"밀도 성수\",\"eatenAt\":\"2026-08-02T14:21:07\",\"photos\":[],\"tags\":[]}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void getSummary_returns200() throws Exception {
		BreadRecordSummaryResponse response = new BreadRecordSummaryResponse(
			6L, 2L, List.of("서울"), List.of("식빵"), List.of(), 50);
		given(summaryService.getSummary(isNull(), any(), any())).willReturn(response);

		mockMvc.perform(get("/bread-records/summary")
			.param("from", "2026-01-01T00:00:00")
			.param("to", "2027-01-01T00:00:00"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalBreads").value(6))
			.andExpect(jsonPath("$.data.revisitRate").value(50));
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

	private static MockMultipartFile photoPart(String fileName) {
		return new MockMultipartFile("photos", fileName, "image/jpeg", new byte[] {1, 2, 3});
	}

	@Test
	void analyzePhotos_returns200() throws Exception {
		BreadPhotoAnalyzeResponse response = new BreadPhotoAnalyzeResponse(List.of(
			new PhotoSuggestions("l0", List.of(new TagSuggestion(101L, "참깨 소금빵", 0.6, 0.4))),
			new PhotoSuggestions("l1", List.of())));
		given(photoTagSuggestionService.analyzeForMember(isNull(), eq(472221L), any()))
			.willReturn(response);

		mockMvc.perform(multipart("/bread-records/photos/analyze")
			.file(photoPart("a.jpg"))
			.file(photoPart("b.jpg"))
			.param("storeId", "472221")
			.param("photoKeys", "l0", "l1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.results[0].photoKey").value("l0"))
			.andExpect(jsonPath("$.data.results[0].suggestions[0].menuId").value(101))
			.andExpect(jsonPath("$.data.results[0].suggestions[0].name").value("참깨 소금빵"))
			.andExpect(jsonPath("$.data.results[0].suggestions[0].x").value(0.6))
			.andExpect(jsonPath("$.data.results[1].suggestions").isEmpty());
	}

	@Test
	void analyzePhotos_returns400_whenPhotoKeyCountDiffers() throws Exception {
		mockMvc.perform(multipart("/bread-records/photos/analyze")
			.file(photoPart("a.jpg"))
			.param("storeId", "472221")
			.param("photoKeys", "l0", "l1"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BREAD_RECORD_PHOTO_KEY_COUNT_MISMATCH"));
	}

	@Test
	void analyzePhotos_returns400_whenNoPhoto() throws Exception {
		mockMvc.perform(multipart("/bread-records/photos/analyze")
			.param("storeId", "472221")
			.param("photoKeys", "l0"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void analyzePhotos_returns200WithEmptyResults_whenFeatureDisabled() throws Exception {
		given(photoTagSuggestionService.analyzeForMember(isNull(), eq(472221L), any()))
			.willReturn(new BreadPhotoAnalyzeResponse(List.of(new PhotoSuggestions("l0", List.of()))));

		mockMvc.perform(multipart("/bread-records/photos/analyze")
			.file(photoPart("a.jpg"))
			.param("storeId", "472221")
			.param("photoKeys", "l0"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.results[0].suggestions").isEmpty());
	}

	@Test
	void analyzePhotos_returns429_whenRateLimited() throws Exception {
		given(photoTagSuggestionService.analyzeForMember(isNull(), eq(472221L), any()))
			.willThrow(new BreadLogException(BreadLogErrorCode.PHOTO_ANALYZE_RATE_LIMITED));

		mockMvc.perform(multipart("/bread-records/photos/analyze")
			.file(photoPart("a.jpg"))
			.param("storeId", "472221")
			.param("photoKeys", "l0"))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("BREAD_RECORD_PHOTO_ANALYZE_RATE_LIMITED"));
	}
}

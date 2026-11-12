package sweetgirl.croissant.breadlog.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
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
import org.mockito.ArgumentCaptor;
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
import sweetgirl.croissant.breadlog.dto.response.BreadRecordUpdateResponse;
import sweetgirl.croissant.breadlog.dto.response.NextStoreResponse;
import sweetgirl.croissant.breadlog.enums.RecordType;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.service.BreadPhotoTagSuggestionService;
import sweetgirl.croissant.breadlog.service.BreadRecordCommandService;
import sweetgirl.croissant.breadlog.service.BreadRecordQueryService;
import sweetgirl.croissant.breadlog.service.BreadRecordSummaryService;
import sweetgirl.croissant.breadlog.service.NextStoreQueryService;
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

	@Mock
	NextStoreQueryService nextStoreQueryService;

	@InjectMocks
	BreadRecordController controller;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void getNextStores_passesStoreAndCoordinates() throws Exception {
		given(nextStoreQueryService.nextStores(isNull(), eq(10L), eq(37.5), eq(127.0)))
			.willReturn(List.of(new NextStoreResponse(20L, "밀도 성수", "서울 성동구", 37.5, 127.0, 300,
				NextStoreResponse.Reason.SAVED)));

		mockMvc.perform(get("/bread-records/next-stores")
			.param("storeId", "10").param("latitude", "37.5").param("longitude", "127.0"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].storeId").value(20))
			.andExpect(jsonPath("$.data[0].reason").value("SAVED"))
			.andExpect(jsonPath("$.data[0].distanceMeters").value(300));
	}

	@Test
	void getRecords_returns200() throws Exception {
		BreadRecordResponse response = new BreadRecordResponse(
			11L, 10L, "밀도 성수", "서울_성동구_성수동", null, null, List.of(), null,
			LocalDateTime.of(2026, 8, 2, 14, 21), false, LocalDateTime.of(2026, 8, 4, 12, 0), null,
			List.of(), List.of(), RecordType.NORMAL, "서울 성동구 서울숲2길 32-14", null, 0, 0, null, "stores/10/320.jpg");
		given(queryService.getRecords(isNull(), any(), any(), eq(10L), eq(0), eq(30)))
			.willReturn(new SliceResponse<>(List.of(response), false));

		mockMvc.perform(get("/bread-records")
			.param("from", "2026-08-01T00:00:00")
			.param("to", "2026-09-01T00:00:00")
			.param("storeId", "10"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content[0].id").value(11))
			.andExpect(jsonPath("$.data.content[0].storeName").value("밀도 성수"))
			.andExpect(jsonPath("$.data.content[0].storeThumbnailImageKey").value("stores/10/320.jpg"))
			.andExpect(jsonPath("$.data.content[0].isPublic").value(false))
			.andExpect(jsonPath("$.data.content[0].recordType").value("NORMAL"))
			.andExpect(jsonPath("$.data.content[0].storeAddress").value("서울 성동구 서울숲2길 32-14"))
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
	void changeTowerBread_returns200() throws Exception {
		mockMvc.perform(patch("/bread-records/11/tower-bread")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"towerBread\": \"bagel\"}"))
			.andExpect(status().isOk());

		verify(commandService).changeTowerBread(eq(11L), isNull(), eq("bagel"));
	}

	@Test
	void changeTowerBread_missingTowerBread_returns400() throws Exception {
		mockMvc.perform(patch("/bread-records/11/tower-bread")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isBadRequest());

		verify(commandService, never()).changeTowerBread(any(), any(), any());
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

	// 사진은 선택이다 — 가게만 고른 기록도 저장된다
	@Test
	void create_withoutPhoto_returns201() throws Exception {
		given(commandService.create(isNull(), any(BreadRecordUpsertRequest.class)))
			.willReturn(new BreadRecordCreateResponse(12L, LocalDateTime.of(2026, 8, 2, 14, 21, 7), List.of()));

		mockMvc.perform(post("/bread-records")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"storeName\":\"밀도 성수\",\"eatenAt\":\"2026-08-02T14:21:07\",\"photos\":[],\"tags\":[]}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.recordId").value(12))
			.andExpect(jsonPath("$.data.rewards").isArray());
	}

	// 빵을 많이 붙인 기록 — 또 먹을래요가 10개를 넘어도 저장된다
	@Test
	void create_withElevenLovedBreads_returns201() throws Exception {
		given(commandService.create(isNull(), any(BreadRecordUpsertRequest.class)))
			.willReturn(new BreadRecordCreateResponse(13L, LocalDateTime.of(2026, 8, 2, 14, 21, 7), List.of()));
		List<String> names = java.util.stream.IntStream.rangeClosed(1, 11).mapToObj(index -> "빵" + index).toList();
		String tags = names.stream().map(name -> "{\"name\":\"" + name + "\"}")
			.collect(java.util.stream.Collectors.joining(","));
		String lovedBreads = names.stream().map(name -> "\"" + name + "\"")
			.collect(java.util.stream.Collectors.joining(","));

		mockMvc.perform(post("/bread-records")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"storeName\":\"밀도 성수\",\"eatenAt\":\"2026-08-02T14:21:07\",\"photos\":[],"
				+ "\"tags\":[" + tags + "],\"lovedBreads\":[" + lovedBreads + "]}"))
			.andExpect(status().isCreated());
	}

	// 가게는 여전히 필수다
	@Test
	void create_withoutStore_returns400() throws Exception {
		mockMvc.perform(post("/bread-records")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"eatenAt\":\"2026-08-02T14:21:07\",\"photos\":[],\"tags\":[]}"))
			.andExpect(status().isBadRequest());
	}

	// 사진에 안 붙은 빵 이름 — photoSortOrder · x · y 없이 이름만 온다
	@Test
	void create_withUnpinnedTag_passesNullPosition() throws Exception {
		given(commandService.create(isNull(), any(BreadRecordUpsertRequest.class)))
			.willReturn(new BreadRecordCreateResponse(12L, LocalDateTime.of(2026, 8, 2, 14, 21, 7), List.of()));

		mockMvc.perform(post("/bread-records")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"storeName\":\"밀도 성수\",\"eatenAt\":\"2026-08-02T14:21:07\",\"photos\":[],"
				+ "\"tags\":[{\"name\":\"소금빵\"}],\"requestId\":\"7f1c9a52-0d43-4a0b-9d6e-0f2b6c1a8e11\"}"))
			.andExpect(status().isCreated());

		ArgumentCaptor<BreadRecordUpsertRequest> request = ArgumentCaptor.forClass(BreadRecordUpsertRequest.class);
		then(commandService).should().create(isNull(), request.capture());
		assertThat(request.getValue().tags().get(0).photoSortOrder()).isNull();
		assertThat(request.getValue().tags().get(0).positionX()).isNull();
		assertThat(request.getValue().requestId()).hasToString("7f1c9a52-0d43-4a0b-9d6e-0f2b6c1a8e11");
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
		given(commandService.replace(eq(11L), isNull(), any(BreadRecordUpsertRequest.class)))
			.willReturn(new BreadRecordUpdateResponse(List.of()));

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

	// 이 집 또 갈래요 — 2.0 앱이 보내는 스위치 값
	@Test
	void create_withRevisit_passesIt() throws Exception {
		given(commandService.create(isNull(), any(BreadRecordUpsertRequest.class)))
			.willReturn(new BreadRecordCreateResponse(11L, LocalDateTime.of(2026, 8, 4, 12, 0)));

		mockMvc.perform(post("/bread-records")
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_REQUEST.replace("\"storeId\": 10,", "\"storeId\": 10, \"revisit\": true,")))
			.andExpect(status().isCreated());

		verify(commandService).create(isNull(), argThat(request -> Boolean.TRUE.equals(request.revisit())));
	}

	@Test
	void create_withUnknownChip_returns400() throws Exception {
		String body = VALID_REQUEST.replace("\"y\": 0.42", "\"y\": 0.42, \"rating\": {\"textures\": [\"없는칩\"]}");

		mockMvc.perform(post("/bread-records")
			.contentType(MediaType.APPLICATION_JSON)
			.content(body))
			.andExpect(status().isBadRequest());

		verify(commandService, never()).create(any(), any());
	}

	@Test
	void create_withRating_returns201() throws Exception {
		given(commandService.create(isNull(), any(BreadRecordUpsertRequest.class)))
			.willReturn(new BreadRecordCreateResponse(11L, LocalDateTime.of(2026, 8, 4, 12, 0)));
		String body = VALID_REQUEST.replace("\"y\": 0.42",
			"\"y\": 0.42, \"rating\": {\"again\": true, \"textures\": [\"겉바속촉\"], "
				+ "\"tastes\": [\"짭짤\"], \"memo\": \"겉바속촉\"}");

		mockMvc.perform(post("/bread-records")
			.contentType(MediaType.APPLICATION_JSON)
			.content(body))
			.andExpect(status().isCreated());

		verify(commandService).create(isNull(),
			argThat(request -> Boolean.TRUE.equals(request.tags().get(0).rating().again())
				&& request.tags().get(0).rating().textures().size() == 1));
	}
}

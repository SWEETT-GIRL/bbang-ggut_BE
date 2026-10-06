package sweetgirl.croissant.breadlog.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDateTime;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import sweetgirl.croissant.breadlog.dto.response.BreadRecordCreateResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordPhotoResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordTagResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadTagRatingResponse;
import sweetgirl.croissant.breadlog.enums.TasteChip;
import sweetgirl.croissant.breadlog.enums.TextureChip;
import sweetgirl.croissant.breadlog.service.BreadPhotoTagSuggestionService;
import sweetgirl.croissant.breadlog.service.BreadRecordCommandService;
import sweetgirl.croissant.breadlog.service.BreadRecordQueryService;
import sweetgirl.croissant.breadlog.service.BreadRecordSummaryService;
import sweetgirl.croissant.global.contract.ResponseContractWebMvcTest;
import sweetgirl.croissant.global.response.SliceResponse;

/**
 * 앱이 실제로 읽는 빵로그 응답 필드를 고정한다. 목록 응답은 상세 화면도 같은 캐시로 쓴다.
 * 주석의 파일:라인은 앱 레포 기준 사용처다 (src/utils/bbanglog.ts 의 adaptRecord 가 대부분을 읽는다).
 */
@ContextConfiguration(classes = BreadRecordController.class)
class BreadRecordResponseContractTest extends ResponseContractWebMvcTest {

	/** bbanglog.ts:15 의 LOCAL_DATE_TIME — 맞지 않으면 NaN 이 돼 날짜·정렬이 깨진다 */
	private static final String APP_LOCAL_DATE_TIME = "^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2})?.*";

	@MockitoBean
	private BreadRecordQueryService queryService;

	@MockitoBean
	private BreadRecordCommandService commandService;

	@MockitoBean
	private BreadRecordSummaryService summaryService;

	@MockitoBean
	private BreadPhotoTagSuggestionService photoTagSuggestionService;

	@Test
	void records_keepsFieldsTheAppReads() throws Exception {
		BreadRecordPhotoResponse photo = new BreadRecordPhotoResponse(101L, "a.jpg", "image/jpeg",
			"bread-records/a.jpg", "bread-records/320/a.jpg", "bread-records/640/a.jpg", "bread-records/1024/a.jpg", 0,
			1080, 1440);
		BreadRecordTagResponse tag = new BreadRecordTagResponse(201L, 101L, 0, "튀김소보로", "도넛", 301L, 0.42, 0.58,
			"pill", new BreadTagRatingResponse(true, List.of(TextureChip.바삭), List.of(TasteChip.안느끼함), "또 먹을래"));
		given(queryService.getRecords(any(), isNull(), isNull(), isNull(), anyInt(), anyInt()))
			.willReturn(new SliceResponse<>(List.of(new BreadRecordResponse(1L, 11L, "성심당", "대전 중구", "맛있다",
				"튀김소보로", List.of("튀김소보로"), "탑", LocalDateTime.of(2026, 10, 4, 12, 30, 15), true,
				LocalDateTime.of(2026, 10, 4, 13, 0, 5), LocalDateTime.of(2026, 10, 4, 13, 0, 5), List.of(photo),
				List.of(tag))), false));

		mockMvc.perform(get("/bread-records"))
			.andExpect(status().isOk())
			// hooks/bbanglog.ts:37-38 — 페이지 넘김
			.andExpect(jsonPath("$.data.content").isArray())
			.andExpect(jsonPath("$.data.hasNext").isBoolean())
			// bbanglog.ts:143-148
			.andExpect(jsonPath("$.data.content[0].id").isNumber())
			.andExpect(jsonPath("$.data.content[0].storeId").isNumber())
			.andExpect(jsonPath("$.data.content[0].storeName").isString())
			.andExpect(jsonPath("$.data.content[0].regionKey").isString())
			.andExpect(jsonPath("$.data.content[0].memo").isString())
			// bbanglog.ts:149
			.andExpect(jsonPath("$.data.content[0].lovedBreads").isArray())
			.andExpect(jsonPath("$.data.content[0].lovedBread").isString())
			// bbanglog.ts:150-151 — 형식이 다르면 NaN
			.andExpect(jsonPath("$.data.content[0].eatenAt").value(Matchers.matchesPattern(APP_LOCAL_DATE_TIME)))
			.andExpect(jsonPath("$.data.content[0].createdAt").value(Matchers.matchesPattern(APP_LOCAL_DATE_TIME)))
			// bbanglog.ts:152
			.andExpect(jsonPath("$.data.content[0].towerBread").isString())
			// bbanglog.ts:153 — 키 이름이 isPublic 여야 한다(public 으로 바뀌면 전부 비공개로 보임)
			.andExpect(jsonPath("$.data.content[0].isPublic").value(true))
			// bbanglog.ts:130 — [...r.photos] 라 없으면 목록 전체가 비어 보인다
			.andExpect(jsonPath("$.data.content[0].photos[0].id").isNumber())
			.andExpect(jsonPath("$.data.content[0].photos[0].sortOrder").isNumber())
			.andExpect(jsonPath("$.data.content[0].photos[0].fileName").isString())
			.andExpect(jsonPath("$.data.content[0].photos[0].contentType").isString())
			.andExpect(jsonPath("$.data.content[0].photos[0].awsKey").isString())
			.andExpect(jsonPath("$.data.content[0].photos[0].resizedKey320").isString())
			.andExpect(jsonPath("$.data.content[0].photos[0].resizedKey640").isString())
			.andExpect(jsonPath("$.data.content[0].photos[0].resizedKey1024").isString())
			.andExpect(jsonPath("$.data.content[0].photos[0].width").isNumber())
			.andExpect(jsonPath("$.data.content[0].photos[0].height").isNumber())
			// bbanglog.ts:131-139, record.tsx:192-194 — r.tags.map 이라 없으면 크래시
			.andExpect(jsonPath("$.data.content[0].tags[0].id").isNumber())
			.andExpect(jsonPath("$.data.content[0].tags[0].photoSortOrder").isNumber())
			.andExpect(jsonPath("$.data.content[0].tags[0].name").isString())
			.andExpect(jsonPath("$.data.content[0].tags[0].breadCategory").isString())
			.andExpect(jsonPath("$.data.content[0].tags[0].menuId").isNumber())
			.andExpect(jsonPath("$.data.content[0].tags[0].style").isString())
			// bbanglog.ts:134-135 — 엔티티는 positionX/Y 지만 앱은 x/y 로 읽는다(@JsonProperty)
			.andExpect(jsonPath("$.data.content[0].tags[0].x").value(0.42))
			.andExpect(jsonPath("$.data.content[0].tags[0].y").value(0.58))
			// constants/breadRating.ts:133 — rating 이 있으면 textures·tastes 가 배열이어야 한다
			.andExpect(jsonPath("$.data.content[0].tags[0].rating.again").value(true))
			.andExpect(jsonPath("$.data.content[0].tags[0].rating.textures[0]").value("바삭"))
			// breadRating.ts:121 chipLabel — 라벨("안 느끼함")이 아니라 enum 이름(code)으로 와야 앱이 라벨로 바꾼다
			.andExpect(jsonPath("$.data.content[0].tags[0].rating.tastes[0]").value("안느끼함"))
			.andExpect(jsonPath("$.data.content[0].tags[0].rating.memo").isString());
	}

	@Test
	void create_keepsFieldsTheAppReads() throws Exception {
		given(commandService.create(any(), any()))
			.willReturn(new BreadRecordCreateResponse(1L, LocalDateTime.of(2026, 10, 4, 13, 0, 5)));

		mockMvc
			.perform(post("/bread-records").contentType(MediaType.APPLICATION_JSON).content(
				"{\"storeId\":11,\"storeName\":\"성심당\",\"eatenAt\":\"2026-10-04T12:30:00\",\"tags\":[],"
					+ "\"photos\":[{\"fileName\":\"a.jpg\",\"contentType\":\"image/jpeg\","
					+ "\"awsKey\":\"bread-records/a.jpg\",\"sortOrder\":0}]}"))
			.andExpect(status().isCreated())
			// record.tsx:583 — 없으면 저장 직후 랭킹 묻기·코스 연결이 빠진다
			.andExpect(jsonPath("$.data.recordId").isNumber());
	}
}

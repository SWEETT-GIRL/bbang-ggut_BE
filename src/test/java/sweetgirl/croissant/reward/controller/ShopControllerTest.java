package sweetgirl.croissant.reward.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.reward.code.RewardErrorCode;
import sweetgirl.croissant.reward.dto.response.MyShopResponse;
import sweetgirl.croissant.reward.dto.response.ShopCatalogResponse;
import sweetgirl.croissant.reward.dto.response.ShopPurchaseResponse;
import sweetgirl.croissant.reward.enums.ShopItemKind;
import sweetgirl.croissant.reward.exception.RewardException;
import sweetgirl.croissant.reward.service.ShopCommandService;
import sweetgirl.croissant.reward.service.ShopQueryService;

@ExtendWith(MockitoExtension.class)
class ShopControllerTest {

	private static final ShopCatalogResponse CATALOG = new ShopCatalogResponse(3, List.of(
		new ShopCatalogResponse.Item("pose-joy", ShopItemKind.POSE, "joy", "신났어", 90, null, 10, false),
		new ShopCatalogResponse.Item("prop-flag", ShopItemKind.PROP, null, "빵 깃발", 50, null, 110, true)));

	private MockMvc mockMvc;

	@Mock
	private ShopQueryService shopQueryService;
	@Mock
	private ShopCommandService shopCommandService;

	@InjectMocks
	private ShopController shopController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(shopController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("품목을 버전 ETag 와 함께 내린다")
	void items() throws Exception {
		given(shopQueryService.getCatalog()).willReturn(CATALOG);

		mockMvc.perform(get("/rewards/shop/items"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ETAG, "W/\"3\""))
			.andExpect(jsonPath("$.data.version").value(3))
			.andExpect(jsonPath("$.data.items[0].code").value("pose-joy"))
			.andExpect(jsonPath("$.data.items[0].kind").value("POSE"))
			.andExpect(jsonPath("$.data.items[0].pose").value("joy"))
			.andExpect(jsonPath("$.data.items[0].price").value(90))
			.andExpect(jsonPath("$.data.items[1].isNew").value(true));
	}

	@Test
	@DisplayName("같은 버전을 들고 오면 304 로 본문을 보내지 않는다")
	void itemsNotModified() throws Exception {
		given(shopQueryService.getCatalog()).willReturn(CATALOG);

		mockMvc.perform(get("/rewards/shop/items").header(HttpHeaders.IF_NONE_MATCH, "W/\"3\""))
			.andExpect(status().isNotModified());
	}

	@Test
	@DisplayName("내가 산 것과 지금 쓰는 것을 내린다 — 소품은 characterCode 가 null 이다")
	void myShop() throws Exception {
		given(shopQueryService.getMyShop(any())).willReturn(new MyShopResponse(
			List.of(new MyShopResponse.Owned("dog", "pose-joy"), new MyShopResponse.Owned(null, "prop-flag")),
			Map.of("dog", "pose-joy"), "prop-flag"));

		mockMvc.perform(get("/members/me/rewards/shop"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.owned[0].characterCode").value("dog"))
			.andExpect(jsonPath("$.data.owned[1].characterCode").value((Object)null))
			.andExpect(jsonPath("$.data.looks.dog").value("pose-joy"))
			.andExpect(jsonPath("$.data.homeProp").value("prop-flag"));
	}

	@Test
	@DisplayName("구매하면 남은 잔액을 내린다")
	void purchase() throws Exception {
		given(shopCommandService.purchase(any(), any())).willReturn(new ShopPurchaseResponse(110, List.of()));

		mockMvc.perform(post("/members/me/rewards/shop/purchase")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"itemCode\":\"pose-joy\",\"characterCode\":\"dog\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.crumbs").value(110));
	}

	@Test
	@DisplayName("부스러기가 모자라면 409 REWARD409_1")
	void purchaseInsufficient() throws Exception {
		given(shopCommandService.purchase(any(), any()))
			.willThrow(new RewardException(RewardErrorCode.REWARD_CRUMBS_INSUFFICIENT));

		mockMvc.perform(post("/members/me/rewards/shop/purchase")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"itemCode\":\"pose-joy\",\"characterCode\":\"dog\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("REWARD409_1"));
	}

	@Test
	@DisplayName("품목 없이 구매하려 하면 400 이고 서비스를 부르지 않는다")
	void purchaseWithoutItem() throws Exception {
		mockMvc.perform(post("/members/me/rewards/shop/purchase")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isBadRequest());

		then(shopCommandService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("모습을 비워 보내면 기본 모습으로, 소품을 비워 보내면 치우도록 넘긴다")
	void clearLookAndProp() throws Exception {
		mockMvc.perform(put("/members/me/rewards/shop/look")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"characterCode\":\"dog\",\"itemCode\":null}"))
			.andExpect(status().isOk());
		mockMvc.perform(put("/members/me/rewards/shop/home-prop")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"itemCode\":null}"))
			.andExpect(status().isOk());

		then(shopCommandService).should().changeLook(any(), eq("dog"), isNull());
		then(shopCommandService).should().changeHomeProp(any(), isNull());
	}
}

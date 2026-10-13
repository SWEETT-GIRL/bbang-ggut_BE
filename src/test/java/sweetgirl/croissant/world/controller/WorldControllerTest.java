package sweetgirl.croissant.world.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.reward.code.RewardErrorCode;
import sweetgirl.croissant.reward.dto.response.RewardItemResponse;
import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.exception.RewardException;
import sweetgirl.croissant.reward.service.FriendCommandService;
import sweetgirl.croissant.reward.service.SpendResult;
import sweetgirl.croissant.world.code.WorldErrorCode;
import sweetgirl.croissant.world.dto.response.MemberWorldResponse;
import sweetgirl.croissant.world.dto.response.WorldRouletteResponse;
import sweetgirl.croissant.world.dto.response.WorldWakeResponse;
import sweetgirl.croissant.world.exception.WorldException;
import sweetgirl.croissant.world.service.WorldCommandService;
import sweetgirl.croissant.world.service.WorldQueryService;

@ExtendWith(MockitoExtension.class)
class WorldControllerTest {

	private MockMvc mockMvc;

	@Mock
	private WorldCommandService worldCommandService;
	@Mock
	private WorldQueryService worldQueryService;
	@Mock
	private FriendCommandService friendCommandService;

	@InjectMocks
	private WorldController worldController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(worldController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	private static final MemberWorldResponse STATE = new MemberWorldResponse(
		7, 12L, 12, "mars", "village", "jupiter", 28, 0.066, "dog",
		List.of("mars"), 1, List.of(), null, 4, 20, true);

	@Test
	@DisplayName("내 세계관 상태를 내린다")
	void returnsMemberWorld() throws Exception {
		given(worldQueryService.getMemberWorld(any())).willReturn(STATE);

		mockMvc.perform(get("/members/me/world"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.planet").value("mars"))
			.andExpect(jsonPath("$.data.stage").value("village"))
			.andExpect(jsonPath("$.data.recordCount").value(12))
			.andExpect(jsonPath("$.data.selectedCharacter").value("dog"))
			.andExpect(jsonPath("$.data.level").value(4))
			.andExpect(jsonPath("$.data.levelUpAt").value(20))
			.andExpect(jsonPath("$.data.crowned").value(true))
			.andExpect(jsonPath("$.data.pendingRoulettePlanets[0]").value("mars"))
			// 앱은 키가 있는지로 새 서버인지 가린다. null 이어도 빠지면 안 된다
			.andExpect(content().string(containsString("\"welcomedAt\":null")));
	}

	@Test
	@DisplayName("부스러기로 깨우면 깨어난 친구와 쓴 부스러기를 내린다")
	void wakesWithCrumbs() throws Exception {
		given(friendCommandService.wakeWithCrumbs(any(), eq("frog"))).willReturn(new WorldWakeResponse(
			"frog", 0, 2, true, List.of(RewardItemResponse.spent(
				new SpendResult(true, 600, 700, 100), CrumbReason.WAKE_FRIEND))));

		mockMvc.perform(post("/members/me/world/characters/frog/wake-crumbs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.awakened").value(true))
			.andExpect(jsonPath("$.data.rewards[0].amount").value(-600))
			.andExpect(jsonPath("$.data.rewards[0].reason").value("WAKE_FRIEND"));
	}

	@Test
	@DisplayName("광고를 본 친구를 부스러기로 깨우려 하면 409 REWARD409_3")
	void wakeWithCrumbsAfterAd() throws Exception {
		given(friendCommandService.wakeWithCrumbs(any(), eq("frog")))
			.willThrow(new RewardException(RewardErrorCode.REWARD_WAKE_AD_STARTED));

		mockMvc.perform(post("/members/me/world/characters/frog/wake-crumbs"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("REWARD409_3"));
	}

	@Test
	@DisplayName("친구 이름을 붙인다")
	void renamesFriend() throws Exception {
		mockMvc.perform(patch("/members/me/world/characters/dog/nickname")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"nickname\":\"초코\"}"))
			.andExpect(status().isOk());

		then(friendCommandService).should().rename(any(), eq("dog"), eq("초코"));
	}

	@Test
	@DisplayName("밀린 것을 따라잡은 뒤에 읽는다 — 순서가 뒤집히면 첫 조회가 빈손이 된다")
	void syncsBeforeReading() throws Exception {
		given(worldQueryService.getMemberWorld(any())).willReturn(STATE);

		mockMvc.perform(get("/members/me/world")).andExpect(status().isOk());

		InOrder inOrder = inOrder(worldCommandService, worldQueryService);
		inOrder.verify(worldCommandService).syncProgress(any());
		inOrder.verify(worldQueryService).getMemberWorld(any());
	}

	@Test
	@DisplayName("따라잡기가 실패하면 읽지 않고 그대로 알린다")
	void doesNotReadWhenSyncFails() throws Exception {
		willThrow(new WorldException(WorldErrorCode.WORLD_MASCOT_NOT_CONFIGURED))
			.given(worldCommandService).syncProgress(any());

		mockMvc.perform(get("/members/me/world"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("WORLD500_2"));

		then(worldQueryService).should(never()).getMemberWorld(any());
	}

	@Test
	@DisplayName("친구를 바꾸면 서비스로 넘긴다")
	void selectsCharacter() throws Exception {
		mockMvc.perform(post("/members/me/world/characters/cat/select"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));

		then(worldCommandService).should().selectCharacter(any(), eq("cat"));
	}

	@Test
	@DisplayName("못 만난 친구를 고르면 403 이다")
	void rejectsLockedCharacter() throws Exception {
		willThrow(new WorldException(WorldErrorCode.WORLD_CHARACTER_LOCKED))
			.given(worldCommandService).selectCharacter(any(), eq("quoka"));

		mockMvc.perform(post("/members/me/world/characters/quoka/select"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("WORLD403_1"));
	}

	@Test
	@DisplayName("룰렛 결과로 뽑힌 친구를 내린다")
	void returnsRouletteResult() throws Exception {
		given(worldCommandService.spinRoulette(any(), eq("mars")))
			.willReturn(new WorldRouletteResponse(List.of("frog")));

		mockMvc.perform(post("/members/me/world/planets/mars/roulette"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.characterCodes[0]").value("frog"));
	}

	@Test
	@DisplayName("이미 돌린 행성이면 409 다")
	void rejectsSecondSpin() throws Exception {
		given(worldCommandService.spinRoulette(any(), eq("mars")))
			.willThrow(new WorldException(WorldErrorCode.WORLD_ROULETTE_ALREADY_DONE));

		mockMvc.perform(post("/members/me/world/planets/mars/roulette"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WORLD409_1"));
	}

	@Test
	@DisplayName("티켓으로 깨우기를 서비스로 넘긴다")
	void usesWakeTicket() throws Exception {
		given(worldCommandService.useWakeTicket(any(), eq("frog")))
			.willReturn(new WorldWakeResponse("frog", 0, 2, true));

		mockMvc.perform(post("/members/me/world/characters/frog/wake-ticket"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.awakened").value(true));
	}

	@Test
	@DisplayName("쓸 티켓이 없으면 409 다")
	void rejectsWithoutTicket() throws Exception {
		given(worldCommandService.useWakeTicket(any(), eq("frog")))
			.willThrow(new WorldException(WorldErrorCode.WORLD_NO_WAKE_TICKET));

		mockMvc.perform(post("/members/me/world/characters/frog/wake-ticket"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WORLD409_4"));
	}

	@Test
	@DisplayName("첫 친구 연출 확인을 서비스로 넘긴다")
	void marksWelcomed() throws Exception {
		mockMvc.perform(post("/members/me/world/welcome"))
			.andExpect(status().isOk());

		then(worldCommandService).should().welcome(any());
	}
}

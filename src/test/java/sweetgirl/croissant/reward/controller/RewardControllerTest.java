package sweetgirl.croissant.reward.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

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
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.reward.dto.response.AttendanceResponse;
import sweetgirl.croissant.reward.dto.response.FeedResponse;
import sweetgirl.croissant.reward.dto.response.RewardClaimResponse;
import sweetgirl.croissant.reward.dto.response.RewardHistoryResponse;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse.CarryOver;
import sweetgirl.croissant.reward.service.RewardCommandService;
import sweetgirl.croissant.reward.service.RewardDailyCommandService;
import sweetgirl.croissant.reward.service.RewardQueryService;

@ExtendWith(MockitoExtension.class)
class RewardControllerTest {

	private MockMvc mockMvc;

	@Mock
	private RewardCommandService rewardCommandService;
	@Mock
	private RewardQueryService rewardQueryService;
	@Mock
	private RewardDailyCommandService rewardDailyCommandService;

	@InjectMocks
	private RewardController rewardController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(rewardController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("이관을 먼저 하고 읽는다 — 순서가 뒤집히면 첫 조회의 잔액이 0 으로 나간다")
	void carriesOverBeforeReading() throws Exception {
		CarryOver carryOver = new CarryOver(true, 110, 12);
		given(rewardCommandService.carryOverIfNeeded(any())).willReturn(Optional.of(carryOver));
		given(rewardQueryService.getSummary(any(), eq(carryOver)))
			.willReturn(RewardSummaryResponse.guest(null, null, null));

		mockMvc.perform(get("/members/me/rewards/summary")).andExpect(status().isOk());

		InOrder inOrder = inOrder(rewardCommandService, rewardQueryService);
		inOrder.verify(rewardCommandService).carryOverIfNeeded(any());
		inOrder.verify(rewardQueryService).getSummary(any(), eq(carryOver));
	}

	@Test
	@DisplayName("이관이 없던 조회는 carryOver 없이 읽는다")
	void readsWithoutCarryOver() throws Exception {
		given(rewardCommandService.carryOverIfNeeded(any())).willReturn(Optional.empty());
		given(rewardQueryService.getSummary(any(), isNull())).willReturn(RewardSummaryResponse.guest(null, null, null));

		mockMvc.perform(get("/members/me/rewards/summary"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.crumbs").value(0));
	}

	@Test
	@DisplayName("내역은 크기를 안 주면 30 개씩 읽는다")
	void historyDefaultSize() throws Exception {
		given(rewardQueryService.getHistory(any(), isNull(), eq(30)))
			.willReturn(new RewardHistoryResponse(List.of(), null));

		mockMvc.perform(get("/members/me/rewards/history"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isArray());
	}

	@Test
	@DisplayName("환영 선물을 이미 받았으면 에러가 아니라 alreadyDone 으로 답한다")
	void welcomeAlreadyDone() throws Exception {
		given(rewardCommandService.claimWelcome(any())).willReturn(RewardClaimResponse.done());

		mockMvc.perform(post("/members/me/rewards/welcome"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.alreadyDone").value(true))
			.andExpect(jsonPath("$.data.rewards").isEmpty());
	}

	@Test
	@DisplayName("게스트가 환영 선물을 받으려 하면 401 이 아니라 403 이다")
	void welcomeGuest() throws Exception {
		willThrow(new MemberException(MemberErrorCode.GUEST_LOGIN_REQUIRED))
			.given(rewardCommandService).claimWelcome(any());

		mockMvc.perform(post("/members/me/rewards/welcome")).andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("오늘 이미 출석했으면 에러가 아니라 alreadyDone 과 오늘 결과로 답한다")
	void attendanceAlreadyDone() throws Exception {
		given(rewardDailyCommandService.checkIn(any())).willReturn(new AttendanceResponse(3, 10, true, List.of()));

		mockMvc.perform(post("/members/me/rewards/attendance"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.boardDay").value(3))
			.andExpect(jsonPath("$.data.crumbs").value(10))
			.andExpect(jsonPath("$.data.alreadyDone").value(true));
	}

	@Test
	@DisplayName("밥 룰렛은 멈춘 칸과 받은 양을 내린다")
	void feed() throws Exception {
		given(rewardDailyCommandService.feed(any())).willReturn(new FeedResponse(5, 50, false, List.of()));

		mockMvc.perform(post("/members/me/rewards/feed"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.wedge").value(5))
			.andExpect(jsonPath("$.data.crumbs").value(50));
	}

	@Test
	@DisplayName("게스트가 출석하려 하면 403 이다")
	void attendanceGuest() throws Exception {
		willThrow(new MemberException(MemberErrorCode.GUEST_LOGIN_REQUIRED))
			.given(rewardDailyCommandService).checkIn(any());

		mockMvc.perform(post("/members/me/rewards/attendance")).andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("확인할 id 가 비어 있으면 400 이고 서비스를 부르지 않는다")
	void ackWithoutIds() throws Exception {
		mockMvc.perform(post("/members/me/rewards/pending/ack")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"ids\":[]}"))
			.andExpect(status().isBadRequest());

		then(rewardCommandService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("연출한 보상의 id 를 넘긴다")
	void ack() throws Exception {
		mockMvc.perform(post("/members/me/rewards/pending/ack")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"ids\":[31,32]}"))
			.andExpect(status().isOk());

		then(rewardCommandService).should().acknowledgePending(any(), eq(List.of(31L, 32L)));
	}
}

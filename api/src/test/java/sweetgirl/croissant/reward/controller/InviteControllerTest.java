package sweetgirl.croissant.reward.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.reward.code.RewardErrorCode;
import sweetgirl.croissant.reward.dto.response.InviteAcceptResponse;
import sweetgirl.croissant.reward.dto.response.InviteResponse;
import sweetgirl.croissant.reward.enums.InviteFriendStatus;
import sweetgirl.croissant.reward.exception.RewardException;
import sweetgirl.croissant.reward.service.InviteService;

@ExtendWith(MockitoExtension.class)
class InviteControllerTest {

	private MockMvc mockMvc;

	@Mock
	private InviteService inviteService;

	@InjectMocks
	private InviteController inviteController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(inviteController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("내 초대 현황을 내린다 — 초대받은 적이 없으면 invitedBy 는 null 이다")
	void myInvite() throws Exception {
		given(inviteService.getMyInvite(any())).willReturn(new InviteResponse("ABCD2345", 3,
			List.of(new InviteResponse.Waiting("신난 두쫀쿠", LocalDate.of(2026, 11, 11))), 1, 10, 100, null, false,
			List.of(
				new InviteResponse.Friend("신난 두쫀쿠", InviteFriendStatus.WAITING, LocalDateTime.of(2026, 10, 12, 9, 0),
					LocalDate.of(2026, 11, 11), null),
				new InviteResponse.Friend("졸린 베이글", InviteFriendStatus.REWARDED, LocalDateTime.of(2026, 10, 1, 9, 0),
					null, LocalDateTime.of(2026, 10, 2, 20, 0)))));

		mockMvc.perform(get("/members/me/invite"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.code").value("ABCD2345"))
			.andExpect(jsonPath("$.data.joinedCount").value(3))
			.andExpect(jsonPath("$.data.waiting[0].nickname").value("신난 두쫀쿠"))
			.andExpect(jsonPath("$.data.rewardedCount").value(1))
			.andExpect(jsonPath("$.data.cap").value(10))
			.andExpect(jsonPath("$.data.reward").value(100))
			.andExpect(jsonPath("$.data.invitedBy").value((Object)null))
			.andExpect(jsonPath("$.data.canEnterCode").value(false))
			.andExpect(jsonPath("$.data.friends[0].nickname").value("신난 두쫀쿠"))
			.andExpect(jsonPath("$.data.friends[0].status").value("WAITING"))
			.andExpect(jsonPath("$.data.friends[0].joinedAt").isString())
			.andExpect(jsonPath("$.data.friends[0].expiresOn").value("2026-11-11"))
			.andExpect(jsonPath("$.data.friends[0].rewardedAt").value((Object)null))
			.andExpect(jsonPath("$.data.friends[1].status").value("REWARDED"))
			.andExpect(jsonPath("$.data.friends[1].expiresOn").value((Object)null))
			.andExpect(jsonPath("$.data.friends[1].rewardedAt").isString());
	}

	@Test
	@DisplayName("코드를 넣으면 초대한 사람의 이름을 내린다")
	void accept() throws Exception {
		given(inviteService.accept(any(), eq("ABCD2345"))).willReturn(new InviteAcceptResponse("청순한 포카치아"));

		mockMvc.perform(post("/members/me/invite/accept")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\":\"ABCD2345\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.inviterNickname").value("청순한 포카치아"));
	}

	@Test
	@DisplayName("없는 코드는 404, 다시 가입한 회원은 403")
	void acceptRejected() throws Exception {
		given(inviteService.accept(any(), eq("NOPE2222")))
			.willThrow(new RewardException(RewardErrorCode.REWARD_INVITE_CODE_NOT_FOUND));
		given(inviteService.accept(any(), eq("ABCD2345")))
			.willThrow(new RewardException(RewardErrorCode.REWARD_INVITE_REJOINED));

		mockMvc.perform(post("/members/me/invite/accept").contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\":\"NOPE2222\"}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("REWARD404_4"));
		mockMvc.perform(post("/members/me/invite/accept").contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\":\"ABCD2345\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("REWARD403_2"));
	}

	@Test
	@DisplayName("코드 없이 보내면 400 이고 서비스를 부르지 않는다")
	void acceptWithoutCode() throws Exception {
		mockMvc.perform(post("/members/me/invite/accept").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest());

		then(inviteService).shouldHaveNoInteractions();
	}
}

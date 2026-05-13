package sweetgirl.croissant.member.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.dto.response.ConsentResponse;
import sweetgirl.croissant.member.dto.response.MenuCategoryResponse;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.service.MemberService;

@ExtendWith(MockitoExtension.class)
class MemberControllerTest {

	private MockMvc mockMvc;

	@Mock
	private MemberService memberService;

	@InjectMocks
	private MemberController memberController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(memberController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	// ── GET /users/me/favorite-bread ─────────────────────────────────────────

	@Test
	void getFavoriteBread_returns200WithBreadList_whenMemberExists() throws Exception {
		MenuCategoryResponse response = new MenuCategoryResponse(new String[] {"소금빵", "크루아상"});
		given(memberService.getLikedMenu(any())).willReturn(response);

		mockMvc.perform(get("/users/me/favorite-bread"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.breadKind[0]").value("소금빵"))
			.andExpect(jsonPath("$.data.breadKind[1]").value("크루아상"));
	}

	@Test
	void getFavoriteBread_returns200WithEmptyList_whenNoFavoritesRegistered() throws Exception {
		given(memberService.getLikedMenu(any())).willReturn(new MenuCategoryResponse(new String[] {}));

		mockMvc.perform(get("/users/me/favorite-bread"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.breadKind").isEmpty());
	}

	@Test
	void getFavoriteBread_returns404_whenMemberNotFound() throws Exception {
		given(memberService.getLikedMenu(any()))
			.willThrow(new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

		mockMvc.perform(get("/users/me/favorite-bread"))
			.andExpect(status().isNotFound());
	}

	// ── POST /users/me/favorite-bread ────────────────────────────────────────

	@Test
	void enrollFavoriteBread_returns200_whenValidBreadList() throws Exception {
		willDoNothing().given(memberService).updateLikeMenuCategory(any(), any());

		mockMvc.perform(post("/users/me/favorite-bread")
			.contentType(MediaType.APPLICATION_JSON)
			.content("[\"소금빵\", \"크루아상\", \"베이글\"]"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void enrollFavoriteBread_returns404_whenMemberNotFound() throws Exception {
		willThrow(new MemberException(MemberErrorCode.MEMBER_NOT_FOUND))
			.given(memberService).updateLikeMenuCategory(any(), any());

		mockMvc.perform(post("/users/me/favorite-bread")
			.contentType(MediaType.APPLICATION_JSON)
			.content("[\"소금빵\"]"))
			.andExpect(status().isNotFound());
	}

	@Test
	void enrollFavoriteBread_returns404_whenMenuNotFound() throws Exception {
		willThrow(new MemberException(MemberErrorCode.MENU_NOT_FOUND))
			.given(memberService).updateLikeMenuCategory(any(), any());

		mockMvc.perform(post("/users/me/favorite-bread")
			.contentType(MediaType.APPLICATION_JSON)
			.content("[\"존재하지않는빵\"]"))
			.andExpect(status().isNotFound());
	}

	// ── GET /users/me/consent ─────────────────────────────────────────────────

	@Test
	void getConsent_returns200WithConsentStatus_whenMemberExists() throws Exception {
		ConsentResponse response = new ConsentResponse(true, false, false, null, null);
		given(memberService.getConsent(any())).willReturn(response);

		mockMvc.perform(get("/users/me/consent"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.agreeToTerms").value(true))
			.andExpect(jsonPath("$.data.agreeToMarketing").value(false))
			.andExpect(jsonPath("$.data.agreeToNightMarketing").value(false));
	}

	@Test
	void getConsent_returns404_whenMemberNotFound() throws Exception {
		given(memberService.getConsent(any()))
			.willThrow(new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

		mockMvc.perform(get("/users/me/consent"))
			.andExpect(status().isNotFound());
	}

	// ── PATCH /users/me/consent ───────────────────────────────────────────────

	@Test
	void updateConsent_returns200_whenOnlyAgreeToMarketingProvided() throws Exception {
		ConsentResponse response = new ConsentResponse(true, true, false, null, null);
		given(memberService.updateConsent(any(), any())).willReturn(response);

		mockMvc.perform(patch("/users/me/consent")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"agreeToMarketing\": true}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.agreeToMarketing").value(true));
	}

	@Test
	void updateConsent_returns200_whenOnlyAgreeToNightMarketingProvided() throws Exception {
		ConsentResponse response = new ConsentResponse(true, false, true, null, null);
		given(memberService.updateConsent(any(), any())).willReturn(response);

		mockMvc.perform(patch("/users/me/consent")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"agreeToNightMarketing\": true}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.agreeToNightMarketing").value(true));
	}

	@Test
	void updateConsent_returns200_whenBothFieldsProvided() throws Exception {
		ConsentResponse response = new ConsentResponse(true, true, true, null, null);
		given(memberService.updateConsent(any(), any())).willReturn(response);

		mockMvc.perform(patch("/users/me/consent")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"agreeToMarketing\": true, \"agreeToNightMarketing\": true}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.agreeToMarketing").value(true))
			.andExpect(jsonPath("$.data.agreeToNightMarketing").value(true));
	}
}

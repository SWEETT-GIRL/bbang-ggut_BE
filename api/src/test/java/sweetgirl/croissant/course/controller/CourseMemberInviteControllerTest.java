package sweetgirl.croissant.course.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.dto.response.CourseInviteAcceptResponse;
import sweetgirl.croissant.course.dto.response.CourseMemberInviteResponse;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.service.CourseMemberInviteService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
@DisplayName("CourseMemberInviteController")
class CourseMemberInviteControllerTest {

	private MockMvc mockMvc;

	@Mock
	private CourseMemberInviteService inviteService;

	@InjectMocks
	private CourseMemberInviteController controller;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("POST /courses/{id}/member-invites — 부른 사람 id 를 돌려주고, 빈 목록은 400")
	void invite() throws Exception {
		given(inviteService.invite(any(), eq(10L), eq(List.of(2L, 3L))))
			.willReturn(new CourseMemberInviteResponse(List.of(2L)));

		mockMvc.perform(post("/courses/10/member-invites")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"memberIds\":[2,3]}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.invitedMemberIds[0]").value(2));

		mockMvc.perform(post("/courses/10/member-invites")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"memberIds\":[]}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("POST /courses/{id}/member-invites/accept · decline — 200, 받은 초대 없으면 404 COURSE404_9")
	void acceptAndDecline() throws Exception {
		given(inviteService.accept(any(), eq(10L))).willReturn(new CourseInviteAcceptResponse(10L, false));

		mockMvc.perform(post("/courses/10/member-invites/accept"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.courseId").value(10))
			.andExpect(jsonPath("$.data.alreadyMember").value(false));

		mockMvc.perform(post("/courses/10/member-invites/decline"))
			.andExpect(status().isOk());

		willThrow(new CourseException(CourseErrorCode.COURSE_MEMBER_INVITE_NOT_FOUND))
			.given(inviteService).decline(any(), eq(11L));
		mockMvc.perform(post("/courses/11/member-invites/decline"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("COURSE404_9"));
	}
}

package sweetgirl.croissant.course.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.course.dto.request.CourseMemberInviteRequest;
import sweetgirl.croissant.course.dto.response.CourseInviteAcceptResponse;
import sweetgirl.croissant.course.dto.response.CourseMemberInviteResponse;
import sweetgirl.croissant.course.service.CourseMemberInviteService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Course", description = "빵투어")
@RestController
@RequestMapping("/courses")
@RequiredArgsConstructor
public class CourseMemberInviteController {

	private final CourseMemberInviteService courseMemberInviteService;

	@Operation(summary = "일행 초대(앱 안)", description = "팔로잉 · 팔로워에서 고른 사람을 부른다. 방장 · 일행만. "
		+ "받는 사람에게 INVITE 알림(target COURSE)이 가고 [수락]/[거절]로 답한다. 이미 일행이거나 답하지 않은 초대가 있으면 조용히 빠진다. "
		+ "상대 없음 404 · 나 자신 400 · 차단 403 · 팔로우 관계 아님 400 COURSE400_21 · 큐레이션 403.")
	@PostMapping("/{courseId}/member-invites")
	public ApiResponse<CourseMemberInviteResponse> invite(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId,
			@Valid @RequestBody CourseMemberInviteRequest request) {
		return ApiResponse.ok(courseMemberInviteService.invite(memberId, courseId, request.memberIds()));
	}

	@Operation(summary = "일행 초대 수락", description = "링크 초대 수락과 같은 규칙(게스트 거부 · 정원 · 공개→PRIVATE). "
		+ "답하지 않은 초대가 없으면 404 COURSE404_9. 이미 일행이면 alreadyMember 로 200.")
	@PostMapping("/{courseId}/member-invites/accept")
	public ApiResponse<CourseInviteAcceptResponse> accept(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId) {
		return ApiResponse.ok(courseMemberInviteService.accept(memberId, courseId));
	}

	@Operation(summary = "일행 초대 거절", description = "알림 없음. 답하지 않은 초대가 없으면 404 COURSE404_9.")
	@PostMapping("/{courseId}/member-invites/decline")
	public ApiResponse<Void> decline(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId) {
		courseMemberInviteService.decline(memberId, courseId);
		return ApiResponse.ok(null);
	}
}

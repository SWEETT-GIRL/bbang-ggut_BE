package sweetgirl.croissant.course.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.course.dto.response.CourseProgressResponse;
import sweetgirl.croissant.course.enums.CourseProgressStatus;
import sweetgirl.croissant.course.service.CourseProgressService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Course", description = "코스 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/members/me/course-progresses")
public class MemberCourseProgressController {

	private final CourseProgressService courseProgressService;

	@Operation(summary = "내 코스 진행 이력 조회")
	@GetMapping
	public ResponseEntity<ApiResponse<List<CourseProgressResponse>>> getCourseProgresses(
			@AuthenticationPrincipal Long memberId,
			@RequestParam(defaultValue = "IN_PROGRESS") CourseProgressStatus status) {
		return ResponseEntity.ok(ApiResponse.ok(courseProgressService.getByStatus(memberId, status)));
	}
}

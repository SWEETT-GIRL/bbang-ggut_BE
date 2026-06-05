package sweetgirl.croissant.report.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.report.dto.request.UserReportCreateRequest;
import sweetgirl.croissant.report.dto.response.UserReportCreateResponse;
import sweetgirl.croissant.report.service.UserReportService;

@Tag(name = "Report", description = "신고 API")
@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class UserReportController {

	private final UserReportService userReportService;

	@Operation(summary = "사용자 신고 등록", description = "targetType: REVIEW | MEMBER_PROFILE")
	@PostMapping
	public ResponseEntity<ApiResponse<UserReportCreateResponse>> createReport(
			@AuthenticationPrincipal Long memberId,
			@RequestBody @Valid UserReportCreateRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.created(userReportService.createReport(memberId, request)));
	}
}

package sweetgirl.croissant.abuse.controller;

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
import sweetgirl.croissant.abuse.dto.request.AbuseReportCreateRequest;
import sweetgirl.croissant.abuse.dto.response.AbuseReportCreateResponse;
import sweetgirl.croissant.abuse.service.AbuseReportCommandService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Report", description = "신고 API")
@RestController
@RequestMapping("/abuse-reports")
@RequiredArgsConstructor
public class AbuseReportController {

	private final AbuseReportCommandService abuseReportCommandService;

	@Operation(summary = "사용자 신고 등록", description = "targetType: REVIEW | MEMBER_PROFILE")
	@PostMapping
	public ResponseEntity<ApiResponse<AbuseReportCreateResponse>> createReport(
			@AuthenticationPrincipal Long memberId,
			@RequestBody @Valid AbuseReportCreateRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.created(abuseReportCommandService.createReport(memberId, request)));
	}
}

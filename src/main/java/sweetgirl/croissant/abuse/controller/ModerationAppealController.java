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
import sweetgirl.croissant.abuse.dto.request.ModerationAppealCreateRequest;
import sweetgirl.croissant.abuse.dto.response.ModerationAppealCreateResponse;
import sweetgirl.croissant.abuse.service.ModerationAppealService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Moderation Appeal", description = "이의제기 API")
@RestController
@RequestMapping("/moderation-appeals")
@RequiredArgsConstructor
public class ModerationAppealController {

	private final ModerationAppealService moderationAppealService;

	@Operation(summary = "이의제기 등록", description = "조치 후 30일 이내에 본인에게 적용된 moderation action에 이의제기합니다.")
	@PostMapping
	public ResponseEntity<ApiResponse<ModerationAppealCreateResponse>> createAppeal(
			@AuthenticationPrincipal Long memberId,
			@RequestBody @Valid ModerationAppealCreateRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.created(moderationAppealService.createAppeal(memberId, request)));
	}
}

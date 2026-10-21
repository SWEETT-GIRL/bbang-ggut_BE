package sweetgirl.croissant.breadlog.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.dto.request.TutorialRecordEditRequest;
import sweetgirl.croissant.breadlog.dto.request.TutorialRecordRequest;
import sweetgirl.croissant.breadlog.dto.response.TutorialRecordResponse;
import sweetgirl.croissant.breadlog.dto.response.TutorialRecordStateResponse;
import sweetgirl.croissant.breadlog.service.TutorialRecordCommandService;
import sweetgirl.croissant.breadlog.service.TutorialRecordQueryService;
import sweetgirl.croissant.global.response.ApiResponse;

@RestController
@RequiredArgsConstructor
@RequestMapping("/bread-records/tutorial")
public class TutorialRecordController {
	private final TutorialRecordCommandService commandService;
	private final TutorialRecordQueryService queryService;

	@GetMapping
	public ApiResponse<TutorialRecordStateResponse> state(@AuthenticationPrincipal Long memberId) {
		return ApiResponse.ok(queryService.getState(memberId));
	}

	@PostMapping
	public ResponseEntity<ApiResponse<TutorialRecordResponse>> complete(
			@AuthenticationPrincipal Long memberId, @Valid @RequestBody TutorialRecordRequest request) {
		TutorialRecordResponse response = commandService.complete(memberId, request);
		return ResponseEntity.status(response.created() ? HttpStatus.CREATED : HttpStatus.OK)
			.body(ApiResponse.ok(response));
	}

	@PatchMapping("/{recordId}")
	public ApiResponse<Void> edit(@AuthenticationPrincipal Long memberId, @PathVariable Long recordId,
			@Valid @RequestBody TutorialRecordEditRequest request) {
		commandService.edit(memberId, recordId, request);
		return ApiResponse.ok(null);
	}
}

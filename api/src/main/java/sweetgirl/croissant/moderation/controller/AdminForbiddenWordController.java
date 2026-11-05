package sweetgirl.croissant.moderation.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.moderation.dto.request.ForbiddenWordCreateRequest;
import sweetgirl.croissant.moderation.dto.response.ForbiddenWordResponse;
import sweetgirl.croissant.moderation.service.ForbiddenWordCommandService;
import sweetgirl.croissant.moderation.service.ForbiddenWordQueryService;

/** 열쇠(X-Admin-Key)로 보호되고, 쓰기는 AdminAuditLogFilter 가 자동으로 남긴다. */
@Tag(name = "Admin Forbidden Word", description = "금칙어 관리 — 댓글 · 닉네임 · 공개 빵로그 후기가 같이 본다")
@RestController
@RequestMapping("/api/admin/forbidden-words")
@RequiredArgsConstructor
public class AdminForbiddenWordController {

	private final ForbiddenWordQueryService forbiddenWordQueryService;
	private final ForbiddenWordCommandService forbiddenWordCommandService;

	@Operation(summary = "금칙어 목록", description = "kind 순(ALLOW · BLOCK) · 단어 순.")
	@GetMapping
	public ResponseEntity<ApiResponse<List<ForbiddenWordResponse>>> getAll() {
		return ResponseEntity.ok(ApiResponse.ok(forbiddenWordQueryService.getAll()));
	}

	@Operation(summary = "금칙어 추가", description = "BLOCK 은 막는 말, ALLOW 는 막는 말을 품었지만 괜찮은 말. 같은 (단어, 종류)가 있으면 409.")
	@PostMapping
	public ResponseEntity<ApiResponse<ForbiddenWordResponse>> create(
			@RequestBody @Valid ForbiddenWordCreateRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.created(forbiddenWordCommandService.create(request)));
	}

	@Operation(summary = "금칙어 삭제")
	@DeleteMapping("/{forbiddenWordId}")
	public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long forbiddenWordId) {
		forbiddenWordCommandService.delete(forbiddenWordId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}

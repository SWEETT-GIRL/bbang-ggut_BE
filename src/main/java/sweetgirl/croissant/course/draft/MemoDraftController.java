package sweetgirl.croissant.course.draft;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.course.dto.request.MemoDraftWriteRequest;
import sweetgirl.croissant.course.dto.response.MemoDraftResponse;
import sweetgirl.croissant.course.dto.response.MemoDraftWriteResponse;
import sweetgirl.croissant.global.response.ApiResponse;

/**
 * 메모 초안 자동저장 API. 초안 Redis 가 설정된 환경에서만 뜬다 — 없으면 상세 {@code draftEnabled} 가 false 라 앱이 부르지 않는다.
 */
@Tag(name = "Course", description = "코스 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/courses")
@ConditionalOnExpression(CourseDraftConfig.REDIS_CONFIGURED)
public class MemoDraftController {

	private final MemoDraftCommandService memoDraftCommandService;
	private final MemoDraftQueryService memoDraftQueryService;

	@Operation(summary = "메모 초안 쓰기", description = "치는 대로 부른다. Redis 만 쓴다. 옛 seq 면 현재 seq 를 돌려준다. 503 이면 앱은 PATCH 로.")
	@PutMapping("/{courseId}/stores/{storeId}/memo-draft")
	public ResponseEntity<ApiResponse<MemoDraftWriteResponse>> writeDraft(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId,
			@PathVariable Long storeId,
			@Valid @RequestBody MemoDraftWriteRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(memoDraftCommandService.write(memberId, courseId, storeId, request)));
	}

	@Operation(summary = "아직 DB 에 안 간 메모 초안", description = "화면 첫 진입·소켓 재연결 때 한 번. 이름은 상세 members 에서 찾는다.")
	@GetMapping("/{courseId}/drafts")
	public ResponseEntity<ApiResponse<List<MemoDraftResponse>>> getDrafts(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId) {
		return ResponseEntity.ok(ApiResponse.ok(memoDraftQueryService.getDrafts(memberId, courseId)));
	}
}

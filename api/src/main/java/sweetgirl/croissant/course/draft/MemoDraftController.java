package sweetgirl.croissant.course.draft;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
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
import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.dto.request.MemoDraftWriteRequest;
import sweetgirl.croissant.course.dto.response.MemoDraftResponse;
import sweetgirl.croissant.course.dto.response.MemoDraftWriteResponse;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.global.response.ApiResponse;

/**
 * 메모 초안 자동저장 API. 컨트롤러는 **환경과 무관하게 항상 뜬다** — OpenAPI 명세(FE 계약 검사)에 초안 API 가 늘 들어가야 해서.
 * 초안 Redis 가 설정되지 않은 환경(서비스 빈 없음)에서는 503 {@code COURSE503_1} 을 돌려주고, 앱은 PATCH 로 물러난다.
 * 상세 {@code draftEnabled} 가 false 라 앱이 애초에 부르지 않지만, 불러도 404 가 아니라 "지금은 못 쓴다"가 맞다.
 */
@Tag(name = "Course", description = "코스 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/courses")
public class MemoDraftController {

	private final ObjectProvider<MemoDraftCommandService> memoDraftCommandService;
	private final ObjectProvider<MemoDraftQueryService> memoDraftQueryService;

	@Operation(summary = "메모 초안 쓰기", description = "치는 대로 부른다. Redis 만 쓴다. 옛 seq 면 현재 seq 를 돌려준다. 503 이면 앱은 PATCH 로.")
	@PutMapping("/{courseId}/stores/{storeId}/memo-draft")
	public ResponseEntity<ApiResponse<MemoDraftWriteResponse>> writeDraft(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId,
			@PathVariable Long storeId,
			@Valid @RequestBody MemoDraftWriteRequest request) {
		MemoDraftCommandService service = available(memoDraftCommandService);
		return ResponseEntity.ok(ApiResponse.ok(service.write(memberId, courseId, storeId, request)));
	}

	@Operation(summary = "아직 DB 에 안 간 메모 초안", description = "화면 첫 진입·소켓 재연결 때 한 번. 이름은 상세 members 에서 찾는다.")
	@GetMapping("/{courseId}/drafts")
	public ResponseEntity<ApiResponse<List<MemoDraftResponse>>> getDrafts(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId) {
		MemoDraftQueryService service = available(memoDraftQueryService);
		return ResponseEntity.ok(ApiResponse.ok(service.getDrafts(memberId, courseId)));
	}

	/** 초안 Redis 가 없는 환경 — 서비스 빈이 없다. 404 가 아니라 503 으로, 앱이 PATCH 로 물러나는 기존 경로. */
	private static <T> T available(ObjectProvider<T> provider) {
		T service = provider.getIfAvailable();
		if (service == null) {
			throw new CourseException(CourseErrorCode.COURSE_DRAFT_UNAVAILABLE);
		}
		return service;
	}
}

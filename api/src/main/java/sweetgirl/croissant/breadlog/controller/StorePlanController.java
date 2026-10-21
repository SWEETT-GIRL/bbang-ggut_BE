package sweetgirl.croissant.breadlog.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import sweetgirl.croissant.breadlog.dto.request.StorePlanUpsertRequest;
import sweetgirl.croissant.breadlog.dto.response.StorePlanResponse;
import sweetgirl.croissant.breadlog.service.StorePlanService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "BreadLog", description = "빵 기록 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/store-plans")
public class StorePlanController {

	private final StorePlanService storePlanService;

	@Operation(summary = "내 가게 계획 메모 목록 조회")
	@GetMapping
	public ResponseEntity<ApiResponse<List<StorePlanResponse>>> getPlans(
			@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(storePlanService.getPlans(memberId)));
	}

	@Operation(summary = "가게 계획 메모 저장")
	@PutMapping("/{storeId}")
	public ResponseEntity<ApiResponse<StorePlanResponse>> upsert(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long storeId,
			@Valid @RequestBody StorePlanUpsertRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(storePlanService.upsert(memberId, storeId, request)));
	}

	@Operation(summary = "가게 계획 메모 삭제")
	@DeleteMapping("/{storeId}")
	public ResponseEntity<ApiResponse<Void>> delete(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long storeId) {
		storePlanService.delete(memberId, storeId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}

package sweetgirl.croissant.admin.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.service.AdminBadgeService;
import sweetgirl.croissant.badge.dto.request.AdminBadgeCreateRequest;
import sweetgirl.croissant.badge.dto.request.AdminBadgeUpdateRequest;
import sweetgirl.croissant.badge.dto.response.AdminBadgeResponse;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Admin Badge", description = "어드민 뱃지 관리 API")
@RestController
@RequestMapping("/api/admin/badges")
@RequiredArgsConstructor
public class AdminBadgeController {

	private final AdminBadgeService adminBadgeService;

	@Operation(summary = "뱃지 카탈로그 조회")
	@GetMapping
	public ResponseEntity<ApiResponse<List<AdminBadgeResponse>>> getBadges() {
		return ResponseEntity.ok(ApiResponse.ok(adminBadgeService.getBadges()));
	}

	@Operation(summary = "뱃지 생성")
	@PostMapping
	public ResponseEntity<ApiResponse<Map<String, Long>>> create(
			@Valid @RequestBody AdminBadgeCreateRequest request) {
		Long badgeId = adminBadgeService.create(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(Map.of("badgeId", badgeId)));
	}

	@Operation(summary = "뱃지 수정")
	@PatchMapping("/{badgeId}")
	public ResponseEntity<ApiResponse<Void>> update(
			@PathVariable Long badgeId,
			@Valid @RequestBody AdminBadgeUpdateRequest request) {
		adminBadgeService.update(badgeId, request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "뱃지 비활성화")
	@DeleteMapping("/{badgeId}")
	public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable Long badgeId) {
		adminBadgeService.deactivate(badgeId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}

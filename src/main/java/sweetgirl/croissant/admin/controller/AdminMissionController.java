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
import sweetgirl.croissant.admin.service.AdminMissionService;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.mission.dto.request.AdminMissionCreateRequest;
import sweetgirl.croissant.mission.dto.request.AdminMissionUpdateRequest;
import sweetgirl.croissant.mission.dto.response.AdminMissionResponse;

@Tag(name = "Admin Mission", description = "어드민 미션 관리 API")
@RestController
@RequestMapping("/api/admin/missions")
@RequiredArgsConstructor
public class AdminMissionController {

	private final AdminMissionService adminMissionService;

	@Operation(summary = "미션 카탈로그 조회")
	@GetMapping
	public ResponseEntity<ApiResponse<List<AdminMissionResponse>>> getMissions() {
		return ResponseEntity.ok(ApiResponse.ok(adminMissionService.getMissions()));
	}

	@Operation(summary = "미션 생성")
	@PostMapping
	public ResponseEntity<ApiResponse<Map<String, Long>>> create(
			@Valid @RequestBody AdminMissionCreateRequest request) {
		Long missionId = adminMissionService.create(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(Map.of("missionId", missionId)));
	}

	@Operation(summary = "미션 수정")
	@PatchMapping("/{missionId}")
	public ResponseEntity<ApiResponse<Void>> update(
			@PathVariable Long missionId,
			@Valid @RequestBody AdminMissionUpdateRequest request) {
		adminMissionService.update(missionId, request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "미션 비활성화")
	@DeleteMapping("/{missionId}")
	public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable Long missionId) {
		adminMissionService.deactivate(missionId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}

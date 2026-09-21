package sweetgirl.croissant.admin.controller;

import java.util.List;
import java.util.Map;

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
import sweetgirl.croissant.admin.service.AdminWorldPlanetService;
import sweetgirl.croissant.admin.service.AdminWorldStageService;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.world.dto.request.AdminWorldPlanetCreateRequest;
import sweetgirl.croissant.world.dto.request.AdminWorldPlanetUpdateRequest;
import sweetgirl.croissant.world.dto.request.AdminWorldStageCreateRequest;
import sweetgirl.croissant.world.dto.request.AdminWorldStageUpdateRequest;
import sweetgirl.croissant.world.dto.response.AdminWorldPlanetResponse;
import sweetgirl.croissant.world.dto.response.AdminWorldStageResponse;

@Tag(name = "[Admin] 빵별일지 세계관", description = "행성 · 고도 단계 · 캐릭터 · 에셋 관리")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/world")
public class AdminWorldController {

	private final AdminWorldPlanetService adminWorldPlanetService;
	private final AdminWorldStageService adminWorldStageService;

	@Operation(summary = "행성 목록", description = "여정 순서대로 누적한 도착점(arriveAt)을 함께 내린다 — span 을 고쳤을 때 "
		+ "임계값이 어떻게 되는지 바로 보라고.")
	@GetMapping("/planets")
	public ResponseEntity<ApiResponse<List<AdminWorldPlanetResponse>>> getPlanets() {
		return ResponseEntity.ok(ApiResponse.ok(adminWorldPlanetService.getPlanets()));
	}

	@Operation(summary = "행성 추가", description = "앱 배포 없이 여정에 새 목적지가 생긴다.")
	@PostMapping("/planets")
	public ResponseEntity<ApiResponse<Map<String, String>>> createPlanet(
			@Valid @RequestBody AdminWorldPlanetCreateRequest request) {
		return ResponseEntity.ok(
			ApiResponse.created(Map.of("code", adminWorldPlanetService.create(request))));
	}

	@Operation(summary = "행성 수정", description = "비워 보낸 칸은 지금 값을 그대로 둔다.")
	@PatchMapping("/planets/{code}")
	public ResponseEntity<ApiResponse<Void>> updatePlanet(
			@PathVariable String code,
			@Valid @RequestBody AdminWorldPlanetUpdateRequest request) {
		adminWorldPlanetService.update(code, request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "행성의 고도 단계 목록")
	@GetMapping("/planets/{code}/stages")
	public ResponseEntity<ApiResponse<List<AdminWorldStageResponse>>> getStages(
			@PathVariable String code) {
		return ResponseEntity.ok(ApiResponse.ok(adminWorldStageService.getStages(code)));
	}

	@Operation(summary = "고도 단계 추가", description = "offsetAt 은 행성 도착점 기준 상대 위치다.")
	@PostMapping("/planets/{code}/stages")
	public ResponseEntity<ApiResponse<Map<String, Long>>> createStage(
			@PathVariable String code,
			@Valid @RequestBody AdminWorldStageCreateRequest request) {
		return ResponseEntity.ok(
			ApiResponse.created(Map.of("id", adminWorldStageService.create(code, request))));
	}

	@Operation(summary = "고도 단계 수정")
	@PatchMapping("/stages/{stageId}")
	public ResponseEntity<ApiResponse<Void>> updateStage(
			@PathVariable Long stageId,
			@Valid @RequestBody AdminWorldStageUpdateRequest request) {
		adminWorldStageService.update(stageId, request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "고도 단계 삭제")
	@DeleteMapping("/stages/{stageId}")
	public ResponseEntity<ApiResponse<Void>> deleteStage(@PathVariable Long stageId) {
		adminWorldStageService.delete(stageId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}

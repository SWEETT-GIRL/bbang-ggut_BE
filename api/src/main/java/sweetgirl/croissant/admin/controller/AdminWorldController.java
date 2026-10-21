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
import sweetgirl.croissant.admin.service.AdminWorldAssetService;
import sweetgirl.croissant.admin.service.AdminWorldCharacterService;
import sweetgirl.croissant.admin.service.AdminWorldMemberService;
import sweetgirl.croissant.admin.service.AdminWorldPlanetService;
import sweetgirl.croissant.admin.service.AdminWorldStageService;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.world.dto.request.AdminWorldAssetRegisterRequest;
import sweetgirl.croissant.world.dto.request.AdminWorldAssetUploadRequest;
import sweetgirl.croissant.world.dto.request.AdminWorldCharacterCreateRequest;
import sweetgirl.croissant.world.dto.request.AdminWorldCharacterUpdateRequest;
import sweetgirl.croissant.world.dto.request.AdminWorldMemberGrantRequest;
import sweetgirl.croissant.world.dto.request.AdminWorldPlanetCreateRequest;
import sweetgirl.croissant.world.dto.request.AdminWorldPlanetUpdateRequest;
import sweetgirl.croissant.world.dto.request.AdminWorldStageCreateRequest;
import sweetgirl.croissant.world.dto.request.AdminWorldStageUpdateRequest;
import sweetgirl.croissant.world.dto.response.AdminWorldCharacterResponse;
import sweetgirl.croissant.world.dto.response.AdminWorldMemberResponse;
import sweetgirl.croissant.world.dto.response.AdminWorldPlanetResponse;
import sweetgirl.croissant.world.dto.response.AdminWorldStageResponse;

@Tag(name = "[Admin] 빵별일지 세계관", description = "행성 · 고도 단계 · 캐릭터 · 에셋 관리")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/world")
public class AdminWorldController {

	private final AdminWorldPlanetService adminWorldPlanetService;
	private final AdminWorldCharacterService adminWorldCharacterService;
	private final AdminWorldAssetService adminWorldAssetService;
	private final AdminWorldMemberService adminWorldMemberService;
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

	@Operation(summary = "캐릭터 목록", description = "올라간 포즈 그림 키와, 그 캐릭터를 가진 회원 수를 함께 내린다 — 비활성으로 "
		+ "돌리기 전에 영향 범위를 보라고.")
	@GetMapping("/characters")
	public ResponseEntity<ApiResponse<List<AdminWorldCharacterResponse>>> getCharacters() {
		return ResponseEntity.ok(ApiResponse.ok(adminWorldCharacterService.getCharacters()));
	}

	@Operation(summary = "캐릭터 추가")
	@PostMapping("/characters")
	public ResponseEntity<ApiResponse<Map<String, String>>> createCharacter(
			@Valid @RequestBody AdminWorldCharacterCreateRequest request) {
		return ResponseEntity.ok(
			ApiResponse.created(Map.of("code", adminWorldCharacterService.create(request))));
	}

	@Operation(summary = "캐릭터 수정", description = "이름의 정본이 서버라 여기서 고치면 앱 배포 없이 바뀐다. 지금 마스코트를 "
		+ "내리거나 비활성으로 돌리려면 대신 세울 캐릭터를 함께 지정해야 한다.")
	@PatchMapping("/characters/{code}")
	public ResponseEntity<ApiResponse<Void>> updateCharacter(
			@PathVariable String code,
			@Valid @RequestBody AdminWorldCharacterUpdateRequest request) {
		adminWorldCharacterService.update(code, request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "캐릭터 그림 올릴 자리 받기", description = "키는 서버가 조립한다 — world/characters/{code}/{pose}_v{n}.{ext}. "
		+ "번호가 올라가므로 같은 포즈를 다시 올려도 CDN 과 앱이 옛 그림을 쓰지 않는다.")
	@PostMapping("/characters/{code}/assets/upload-url")
	public ResponseEntity<ApiResponse<PresignedUrlResponse>> createAssetUploadUrl(
			@PathVariable String code,
			@Valid @RequestBody AdminWorldAssetUploadRequest request) {
		return ResponseEntity.ok(
			ApiResponse.ok(adminWorldAssetService.createUploadUrl(code, request)));
	}

	@Operation(summary = "캐릭터 그림 등록", description = "올리기를 마친 키를 카탈로그에 붙인다.")
	@PostMapping("/characters/{code}/assets")
	public ResponseEntity<ApiResponse<Void>> registerAsset(
			@PathVariable String code,
			@Valid @RequestBody AdminWorldAssetRegisterRequest request) {
		adminWorldAssetService.register(code, request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "캐릭터 그림 등록 해제", description = "카탈로그에서만 뺀다. S3 객체는 남는다 — 잘못 지웠을 때 키만 다시 등록하면 된다.")
	@DeleteMapping("/characters/{code}/assets/{pose}")
	public ResponseEntity<ApiResponse<Void>> unregisterAsset(
			@PathVariable String code,
			@PathVariable String pose) {
		adminWorldAssetService.unregister(code, pose);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "회원의 세계관 상태 조회", description = "\"광고를 봤는데 안 깨어났어요\" 같은 문의에 광고 적립 수와 도착 · 룰렛 이력으로 답한다.")
	@GetMapping("/members/{memberId}")
	public ResponseEntity<ApiResponse<AdminWorldMemberResponse>> getMemberWorld(
			@PathVariable Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(adminWorldMemberService.getMemberWorld(memberId)));
	}

	@Operation(summary = "회원에게 지급 · 진행도 조정", description = "CS · 광고 오류 보상용. 비워 보낸 칸은 건드리지 않는다. 진행도를 실제 기록 수보다 "
		+ "낮추면 효과가 없다 — 조회가 큰 쪽을 쓴다.")
	@PostMapping("/members/{memberId}/grants")
	public ResponseEntity<ApiResponse<Void>> grant(
			@PathVariable Long memberId,
			@Valid @RequestBody AdminWorldMemberGrantRequest request) {
		adminWorldMemberService.grant(memberId, request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}

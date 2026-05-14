package sweetgirl.croissant.admin.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.dto.request.AdminStoreCreateRequest;
import sweetgirl.croissant.admin.dto.request.AdminStoreUpdateRequest;
import sweetgirl.croissant.admin.dto.response.AdminStoreResponse;
import sweetgirl.croissant.admin.service.AdminStoreService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Admin Store", description = "어드민 가게 관리 API")
@RestController
@RequestMapping("/api/admin/stores")
@RequiredArgsConstructor
public class AdminStoreController {

	private final AdminStoreService adminStoreService;

	@Operation(summary = "가게 등록", description = "어드민에서 새 가게를 등록합니다. "
		+ "coord(lat/lng)로 PostGIS Point를 생성하며, hours는 mon~sun 키로 영업시간을 설정합니다.")
	@PostMapping
	public ResponseEntity<ApiResponse<AdminStoreResponse>> createStore(
			@Valid @RequestBody AdminStoreCreateRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(adminStoreService.createStore(request)));
	}

	@Operation(summary = "가게 목록 조회", description = "가게 목록을 페이지네이션으로 조회합니다. "
		+ "keyword로 이름 검색, status로 활성/삭제 필터링이 가능합니다.")
	@GetMapping
	public ResponseEntity<ApiResponse<Page<AdminStoreResponse>>> getStores(
			@Parameter(description = "가게 이름 검색어 (부분 일치)") @RequestParam(required = false) String keyword,
			@Parameter(description = "가게 상태 (active | inactive)") @RequestParam(required = false) String status,
			@Parameter(description = "페이지 번호 (0부터 시작)") @RequestParam(defaultValue = "0") int page,
			@Parameter(description = "페이지 크기") @RequestParam(defaultValue = "20") int size) {
		return ResponseEntity.ok(
			ApiResponse.ok(adminStoreService.getStores(keyword, status, PageRequest.of(page, size))));
	}

	@Operation(summary = "가게 상세 조회", description = "가게 ID로 상세 정보를 조회합니다. 영업시간, 대표 사진, 구독 수(alarmCount)를 포함합니다.")
	@GetMapping("/{storeId}")
	public ResponseEntity<ApiResponse<AdminStoreResponse>> getStoreDetail(
			@Parameter(description = "가게 ID") @PathVariable Long storeId) {
		return ResponseEntity.ok(ApiResponse.ok(adminStoreService.getStoreDetail(storeId)));
	}

	@Operation(summary = "가게 수정", description = "가게 정보를 수정합니다. 모든 필드는 선택적이며, "
		+ "archivedAt이 non-null이면 상태가 DELETED로 변경됩니다.")
	@PutMapping("/{storeId}")
	public ResponseEntity<ApiResponse<Void>> updateStore(
			@Parameter(description = "가게 ID") @PathVariable Long storeId,
			@RequestBody AdminStoreUpdateRequest request) {
		adminStoreService.updateStore(storeId, request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "가게 삭제", description = "가게를 soft-delete합니다 (status → DELETED).")
	@DeleteMapping("/{storeId}")
	public ResponseEntity<ApiResponse<Void>> deleteStore(
			@Parameter(description = "가게 ID") @PathVariable Long storeId) {
		adminStoreService.deleteStore(storeId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}

package sweetgirl.croissant.admin.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
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

	@Operation(summary = "가게 생성")
	@PostMapping
	public ResponseEntity<ApiResponse<AdminStoreResponse>> createStore(
			@Valid @RequestBody AdminStoreCreateRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(adminStoreService.createStore(request)));
	}

	@Operation(summary = "가게 목록 조회")
	@GetMapping
	public ResponseEntity<ApiResponse<Page<AdminStoreResponse>>> getStores(
			@RequestParam(required = false) String keyword,
			@RequestParam(required = false) String status,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ResponseEntity.ok(
			ApiResponse.ok(adminStoreService.getStores(keyword, status, PageRequest.of(page, size))));
	}

	@Operation(summary = "가게 상세 조회")
	@GetMapping("/{storeId}")
	public ResponseEntity<ApiResponse<AdminStoreResponse>> getStoreDetail(@PathVariable Long storeId) {
		return ResponseEntity.ok(ApiResponse.ok(adminStoreService.getStoreDetail(storeId)));
	}

	@Operation(summary = "가게 수정")
	@PutMapping("/{storeId}")
	public ResponseEntity<ApiResponse<Void>> updateStore(
			@PathVariable Long storeId,
			@RequestBody AdminStoreUpdateRequest request) {
		adminStoreService.updateStore(storeId, request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}

package sweetgirl.croissant.admin.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.dto.request.AdminMenuCreateRequest;
import sweetgirl.croissant.admin.dto.request.AdminMenuUpdateRequest;
import sweetgirl.croissant.admin.dto.response.AdminMenuResponse;
import sweetgirl.croissant.admin.service.AdminMenuService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Admin Menu", description = "어드민 메뉴 관리 API")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminMenuController {

	private final AdminMenuService adminMenuService;

	@Operation(summary = "메뉴 생성")
	@PostMapping("/stores/{storeId}/menus")
	public ResponseEntity<ApiResponse<AdminMenuResponse>> createMenu(
			@PathVariable Long storeId,
			@Valid @RequestBody AdminMenuCreateRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(adminMenuService.createMenu(storeId, request)));
	}

	@Operation(summary = "가게별 메뉴 목록 조회 (soft-delete 포함)")
	@GetMapping("/stores/{storeId}/menus")
	public ResponseEntity<ApiResponse<List<AdminMenuResponse>>> getStoreMenus(
			@PathVariable Long storeId) {
		return ResponseEntity.ok(ApiResponse.ok(adminMenuService.getStoreMenus(storeId)));
	}

	@Operation(summary = "메뉴 수정")
	@PutMapping("/menus/{menuId}")
	public ResponseEntity<ApiResponse<Void>> updateMenu(
			@PathVariable Long menuId,
			@RequestBody AdminMenuUpdateRequest request) {
		adminMenuService.updateMenu(menuId, request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}

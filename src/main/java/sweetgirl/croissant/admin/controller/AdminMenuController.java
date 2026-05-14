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
import io.swagger.v3.oas.annotations.Parameter;
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

	@Operation(summary = "메뉴 등록", description = "가게에 새 메뉴를 등록합니다. "
		+ "price는 '3000원' 형식, category는 MenuKind 코드명(예: BREAD, CAKE)을 사용합니다.")
	@PostMapping("/stores/{storeId}/menus")
	public ResponseEntity<ApiResponse<AdminMenuResponse>> createMenu(
			@Parameter(description = "가게 ID") @PathVariable Long storeId,
			@Valid @RequestBody AdminMenuCreateRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(adminMenuService.createMenu(storeId, request)));
	}

	@Operation(summary = "가게별 메뉴 목록 조회", description = "가게에 등록된 전체 메뉴를 조회합니다. "
		+ "soft-delete된 메뉴(status: inactive)도 포함하여 반환합니다.")
	@GetMapping("/stores/{storeId}/menus")
	public ResponseEntity<ApiResponse<List<AdminMenuResponse>>> getStoreMenus(
			@Parameter(description = "가게 ID") @PathVariable Long storeId) {
		return ResponseEntity.ok(ApiResponse.ok(adminMenuService.getStoreMenus(storeId)));
	}

	@Operation(summary = "메뉴 수정", description = "메뉴 정보를 수정합니다. "
		+ "status를 'inactive'로 설정하면 soft-delete(deletedAt 기록)되며, 이후 복구는 지원하지 않습니다.")
	@PutMapping("/menus/{menuId}")
	public ResponseEntity<ApiResponse<Void>> updateMenu(
			@Parameter(description = "메뉴 ID") @PathVariable Long menuId,
			@RequestBody AdminMenuUpdateRequest request) {
		adminMenuService.updateMenu(menuId, request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}

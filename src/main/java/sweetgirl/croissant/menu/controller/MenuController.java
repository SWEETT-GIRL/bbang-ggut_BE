package sweetgirl.croissant.menu.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.menu.dto.response.MenuDetailResponse;
import sweetgirl.croissant.menu.dto.response.StoreMenuResponse;
import sweetgirl.croissant.menu.service.MenuService;

@Tag(name = "Menu", description = "메뉴 API")
@RestController
@RequiredArgsConstructor
public class MenuController {

	private final MenuService menuService;

	@Operation(summary = "가게 메뉴 목록 조회", description = "추천수 내림차순 전체 반환. 인기메뉴(top5) / 카테고리 필터는 클라이언트에서 처리.")
	@GetMapping("/stores/{storeId}/menus")
	public ResponseEntity<ApiResponse<List<StoreMenuResponse>>> getStoreMenus(@PathVariable Long storeId) {
		return ResponseEntity.ok(ApiResponse.ok(menuService.getStoreMenus(storeId)));
	}

	@Operation(summary = "메뉴 상세 조회")
	@GetMapping("/menus/{menuId}")
	public ResponseEntity<ApiResponse<MenuDetailResponse>> getMenuDetail(@PathVariable Long menuId) {
		return ResponseEntity.ok(ApiResponse.ok(menuService.getMenuDetail(menuId)));
	}
}

package sweetgirl.croissant.menu.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

	@Operation(summary = "메뉴 상세 조회", description = "menuId로 메뉴 상세 정보(이름·가격·사진·카테고리·추천 수)를 반환합니다.")
	@GetMapping("/menus/{menuId}")
	public ResponseEntity<ApiResponse<MenuDetailResponse>> getMenuDetail(@PathVariable Long menuId) {
		return ResponseEntity.ok(ApiResponse.ok(menuService.getMenuDetail(menuId)));
	}

	@Operation(summary = "메뉴 추천", description = "로그인한 사용자가 메뉴를 추천합니다. 이미 추천한 경우 409를 반환합니다.")
	@PostMapping("/menus/{menuId}/recommend")
	public ResponseEntity<ApiResponse<Void>> recommendMenu(
			@PathVariable Long menuId,
			@AuthenticationPrincipal Long memberId) {
		menuService.recommendMenu(menuId, memberId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "메뉴 추천 취소", description = "로그인한 사용자의 메뉴 추천을 취소합니다. 추천하지 않은 메뉴에 요청 시 400을 반환합니다.")
	@DeleteMapping("/menus/{menuId}/recommend")
	public ResponseEntity<ApiResponse<Void>> cancelMenuRecommend(
			@PathVariable Long menuId,
			@AuthenticationPrincipal Long memberId) {
		menuService.cancelMenuRecommend(menuId, memberId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

}

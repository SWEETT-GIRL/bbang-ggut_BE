package sweetgirl.croissant.menu.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.menu.dto.request.MenuCreateRequest;
import sweetgirl.croissant.menu.dto.request.MenuKindConfirmRequest;
import sweetgirl.croissant.menu.dto.response.MenuCategoryResponse;
import sweetgirl.croissant.menu.dto.response.MenuDetailResponse;
import sweetgirl.croissant.menu.dto.response.MenuRegistrationResult;
import sweetgirl.croissant.menu.dto.response.StoreMenuResponse;
import sweetgirl.croissant.menu.service.MenuCommandService;
import sweetgirl.croissant.menu.service.MenuQueryService;

@Tag(name = "Menu", description = "메뉴 API")
@RestController
@RequiredArgsConstructor
public class MenuController {

	private final MenuQueryService menuQueryService;
	private final MenuCommandService menuCommandService;

	@Operation(summary = "메뉴 카테고리 목록 조회", description = "등록된 모든 메뉴 카테고리(id, name)를 반환합니다.")
	@GetMapping("/menu-categories")
	public ResponseEntity<ApiResponse<List<MenuCategoryResponse>>> getCategories() {
		return ResponseEntity.ok(ApiResponse.ok(menuQueryService.getCategories()));
	}

	@Operation(summary = "가게 메뉴 목록 조회", description = "추천수 내림차순 전체 반환. 인기메뉴(top5) / 카테고리 필터는 클라이언트에서 처리.")
	@GetMapping("/stores/{storeId}/menus")
	public ResponseEntity<ApiResponse<List<StoreMenuResponse>>> getStoreMenus(
			@PathVariable Long storeId,
			@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(menuQueryService.getStoreMenus(storeId, memberId)));
	}

	@Operation(summary = "가게 메뉴 즉시 등록", description = "메뉴명만으로 등록하며 같은 정규화 이름이 있으면 기존 메뉴를 반환합니다.")
	@PostMapping("/stores/{storeId}/menus")
	public ResponseEntity<ApiResponse<StoreMenuResponse>> createStoreMenu(
			@PathVariable Long storeId,
			@AuthenticationPrincipal Long memberId,
			@Valid @RequestBody MenuCreateRequest request) {
		MenuRegistrationResult result = menuCommandService.registerMenu(storeId, memberId, request);
		StoreMenuResponse response = menuQueryService.getStoreMenu(result.menuId(), memberId)
			.withSuggestedKind(result.suggestedKind());
		HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
		return ResponseEntity.status(status).body(ApiResponse.ok(response));
	}

	@Operation(summary = "메뉴 상세 조회", description = "menuId로 메뉴 상세 정보(이름·가격·사진·카테고리·좋아요 수)를 반환합니다.")
	@GetMapping("/menus/{menuId}")
	public ResponseEntity<ApiResponse<MenuDetailResponse>> getMenuDetail(
			@PathVariable Long menuId,
			@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(menuQueryService.getMenuDetail(menuId, memberId)));
	}

	@Operation(summary = "메뉴 종류 확정", description = "회원이 직접 등록한 메뉴의 종류(kind)를 AI 제안 칩에서 골라 확정합니다. 본인이 등록한 메뉴가 아니면 403.")
	@PatchMapping("/menus/{menuId}/kind")
	public ResponseEntity<ApiResponse<Void>> confirmMenuKind(
			@PathVariable Long menuId,
			@AuthenticationPrincipal Long memberId,
			@Valid @RequestBody MenuKindConfirmRequest request) {
		menuCommandService.confirmKind(menuId, memberId, request.kind());
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "메뉴 좋아요", description = "로그인한 사용자가 메뉴에 좋아요를 누릅니다. 이미 좋아요한 경우 409를 반환합니다.")
	@PostMapping("/menus/{menuId}/like")
	public ResponseEntity<ApiResponse<Void>> likeMenu(
			@PathVariable Long menuId,
			@AuthenticationPrincipal Long memberId) {
		menuCommandService.likeMenu(menuId, memberId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "메뉴 좋아요 취소", description = "로그인한 사용자의 메뉴 좋아요를 취소합니다. 좋아요하지 않은 메뉴에 요청 시 400을 반환합니다.")
	@DeleteMapping("/menus/{menuId}/like")
	public ResponseEntity<ApiResponse<Void>> cancelMenuLike(
			@PathVariable Long menuId,
			@AuthenticationPrincipal Long memberId) {
		menuCommandService.cancelMenuLike(menuId, memberId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

}

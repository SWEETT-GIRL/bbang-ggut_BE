package sweetgirl.croissant.store.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.reward.dto.response.SaveStoreResponse;
import sweetgirl.croissant.store.dto.response.SavedStoreResponse;
import sweetgirl.croissant.store.service.SavedStoreCommandService;
import sweetgirl.croissant.store.service.SavedStoreQueryService;

@Tag(name = "SavedStore", description = "찜한가게 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/stores")
public class SavedStoreController {

	private final SavedStoreQueryService savedStoreQueryService;
	private final SavedStoreCommandService savedStoreCommandService;

	@Operation(summary = "찜한가게 목록 조회")
	@GetMapping("/saved")
	public ResponseEntity<ApiResponse<List<SavedStoreResponse>>> getSavedStores(
			@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(savedStoreQueryService.getSavedStores(memberId)));
	}

	@Operation(summary = "가게 찜 추가")
	@PostMapping("/{storeId}/save")
	public ResponseEntity<ApiResponse<SaveStoreResponse>> saveStore(
			@PathVariable Long storeId,
			@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(savedStoreCommandService.saveStore(memberId, storeId)));
	}

	@Operation(summary = "가게 찜 해제")
	@DeleteMapping("/{storeId}/save")
	public ResponseEntity<ApiResponse<Void>> unsaveStore(
			@PathVariable Long storeId,
			@AuthenticationPrincipal Long memberId) {
		savedStoreCommandService.unsaveStore(memberId, storeId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}

package sweetgirl.croissant.store.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.store.dto.StoreDetailResponse;
import sweetgirl.croissant.store.dto.StoreSearchResponse;
import sweetgirl.croissant.store.dto.SubscribedStoreResponse;
import sweetgirl.croissant.store.service.StoreService;

@Tag(name = "Store", description = "빵집 API")
@RestController
@RequestMapping("/stores")
@RequiredArgsConstructor
public class StoreController {

	private final StoreService storeService;

	@Operation(summary = "빵집 상세 조회", description = "빵집 ID로 상세 정보를 조회합니다.")
	@GetMapping("/{id}")
	public ResponseEntity<ApiResponse<StoreDetailResponse>> getStoreDetail(@PathVariable Long id) {
		return ResponseEntity.ok(ApiResponse.ok(storeService.getStoreDetail(id)));
	}

	@Operation(summary = "알림받는 빵집 목록 조회", description = "사용자 ID로 알림받는 빵집 목록을 조회합니다.")
	@GetMapping("/subscribed")
	public ResponseEntity<ApiResponse<SliceResponse<SubscribedStoreResponse>>> getSubscribedStore(
			@AuthenticationPrincipal Long memberId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size) {
		return ResponseEntity
			.ok(ApiResponse.ok(storeService.getSubscribedStores(memberId, PageRequest.of(page, size))));
	}

	@Operation(summary = "빵집 검색", description = "빵집 이름으로 검색합니다. DB에 결과 없으면 카카오 로컬 API로 fallback합니다.")
	@GetMapping("/search")
	public ResponseEntity<ApiResponse<SliceResponse<StoreSearchResponse>>> searchStores(
			@Parameter(description = "검색어") @RequestParam String keyword,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "15") int size) {
		return ResponseEntity.ok(ApiResponse.ok(storeService.searchStores(keyword, PageRequest.of(page, size))));
	}
}

package sweetgirl.croissant.store.controller;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.store.dto.response.NearbyStoreResponse;
import sweetgirl.croissant.store.dto.response.StoreDetailResponse;
import sweetgirl.croissant.store.dto.response.StoreSearchResponse;
import sweetgirl.croissant.store.dto.response.SubscribedStoreResponse;
import sweetgirl.croissant.store.dto.response.TopSubscribedStoreResponse;
import sweetgirl.croissant.store.service.StoreService;

@Tag(name = "Store", description = "빵집 API")
@RestController
@RequestMapping("/stores")
@RequiredArgsConstructor
public class StoreController {

	private final StoreService storeService;

	@Operation(summary = "빵집 상세 조회", description = "빵집 ID로 상세 정보를 조회합니다.")
	@GetMapping("/{storeId}")
	public ResponseEntity<ApiResponse<StoreDetailResponse>> getStoreDetail(@AuthenticationPrincipal Long memberId,
			@PathVariable Long storeId) {
		return ResponseEntity.ok(ApiResponse.ok(storeService.getStoreDetail(memberId, storeId)));
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

	@Operation(summary = "근처 빵집 목록 조회")
	@GetMapping("/nearby")
	public ResponseEntity<ApiResponse<SliceResponse<NearbyStoreResponse>>> getNearbyStore(
			@RequestParam double lat, @RequestParam double lng,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size) {
		return ResponseEntity.ok(
			ApiResponse.ok(storeService.getNearbyStores(lng, lat, PageRequest.of(page, size))));
	}

	@Operation(summary = "인기 빵집 목록 조회", description = "구독 수 내림차순으로 상위 빵집 목록을 조회합니다.")
	@GetMapping("/popular")
	public ResponseEntity<ApiResponse<List<TopSubscribedStoreResponse>>> getTopSubscribedStores(
			@RequestParam double lat, @RequestParam double lng) {
		return ResponseEntity.ok(ApiResponse.ok(storeService.getTopSubscribedStores(lng, lat)));
	}

	@Operation(summary = "빵집 구독 (빵알람 등록)", description = "빵집을 구독합니다. 이미 구독 중이면 409를 반환합니다.")
	@PostMapping("/{storeId}/subscribe")
	public ResponseEntity<ApiResponse<Void>> subscribeStore(
			@PathVariable Long storeId,
			@AuthenticationPrincipal Long memberId) {
		storeService.subscribeStore(storeId, memberId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "빵집 구독 취소 (빵알람 해제)", description = "빵집 구독을 취소합니다. 구독 중이 아니면 400을 반환합니다.")
	@DeleteMapping("/{storeId}/subscribe")
	public ResponseEntity<ApiResponse<Void>> unsubscribeStore(
			@PathVariable Long storeId,
			@AuthenticationPrincipal Long memberId) {
		storeService.unsubscribeStore(storeId, memberId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}

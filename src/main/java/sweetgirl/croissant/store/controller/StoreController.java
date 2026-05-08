package sweetgirl.croissant.store.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.store.dto.StoreDetailResponse;
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
	public ResponseEntity<ApiResponse<List<SubscribedStoreResponse>>> getSubscribedStore() {
		// TODO: 인증 구현 후 SecurityContext에서 member 꺼내도록 수정
		return ResponseEntity.ok(ApiResponse.ok(storeService.getSubscribedStores(null)));
	}
}

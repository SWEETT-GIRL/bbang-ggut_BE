package sweetgirl.croissant.store.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.store.dto.response.BusinessHourSyncResponse;
import sweetgirl.croissant.store.service.StoreBusinessHourSyncService;

@Tag(name = "Store Admin", description = "빵집 어드민 API")
@RestController
@RequestMapping("/admin/stores")
@RequiredArgsConstructor
public class StoreAdminController {

	private final StoreBusinessHourSyncService storeBusinessHourSyncService;

	@Operation(summary = "전체 영업시간 동기화", description = "모든 가게 영업시간을 Google Places API로 동기화합니다.")
	@PostMapping("/business-hours/sync")
	public ResponseEntity<ApiResponse<BusinessHourSyncResponse>> syncAllBusinessHours() {
		return ResponseEntity.ok(ApiResponse.ok(storeBusinessHourSyncService.syncAll()));
	}

	@Operation(summary = "단건 영업시간 동기화", description = "특정 가게 영업시간을 Google Places API로 동기화합니다.")
	@PostMapping("/{storeId}/business-hours/sync")
	public ResponseEntity<ApiResponse<Void>> syncStoreBusinessHours(@PathVariable Long storeId) {
		storeBusinessHourSyncService.syncByStoreId(storeId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}

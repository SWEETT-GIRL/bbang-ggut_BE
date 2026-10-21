package sweetgirl.dataops.signal.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.dataops.global.response.ApiResponse;
import sweetgirl.dataops.signal.dto.response.StoreSignalResponse;
import sweetgirl.dataops.signal.service.StoreSignalService;

@Tag(name = "StoreSignal", description = "가게 신호(태그 소스) — 최신 스냅샷을 재파싱한 취향 판정 재료 (코스 추천 1단계)")
@RestController
@RequestMapping("/api/store-signals")
@RequiredArgsConstructor
public class StoreSignalController {

	private final StoreSignalService storeSignalService;

	@Operation(summary = "신호 조회 (저장본)")
	@GetMapping("/{storeId}")
	public ResponseEntity<ApiResponse<StoreSignalResponse>> getSignal(@PathVariable Long storeId) {
		return ResponseEntity.ok(ApiResponse.ok(storeSignalService.getSignal(storeId)));
	}

	@Operation(summary = "파싱 미리보기", description = "스냅샷 하나를 신호 파서로만 돌려 보여준다 — 저장 없음, 파서 눈검사용")
	@GetMapping("/preview/{snapshotId}")
	public ResponseEntity<ApiResponse<StoreSignalResponse>> preview(@PathVariable Long snapshotId) {
		return ResponseEntity.ok(ApiResponse.ok(storeSignalService.preview(snapshotId)));
	}

	@Operation(summary = "가게 한 곳 재빌드", description = "최신 NAVER 스냅샷을 파싱해 store_signal 에 upsert")
	@PostMapping("/{storeId}/rebuild")
	public ResponseEntity<ApiResponse<StoreSignalResponse>> rebuild(@PathVariable Long storeId) {
		return ResponseEntity.ok(ApiResponse.ok(storeSignalService.rebuild(storeId)));
	}

	@Operation(summary = "전체 재빌드", description = "스냅샷 있는 전 가게 — 파서 규칙 변경 시 (가게별 트랜잭션, 실패는 건너뜀)")
	@PostMapping("/rebuild-all")
	public ResponseEntity<ApiResponse<Integer>> rebuildAll() {
		return ResponseEntity.ok(ApiResponse.ok(storeSignalService.rebuildAll()));
	}
}

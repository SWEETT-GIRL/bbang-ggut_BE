package sweetgirl.dataops.sync.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.dataops.global.response.ApiResponse;
import sweetgirl.dataops.global.response.PageResponse;
import sweetgirl.dataops.sync.dto.request.ChangeTierRequest;
import sweetgirl.dataops.sync.dto.response.DueStoreResponse;
import sweetgirl.dataops.sync.dto.response.ObservationSampleResponse;
import sweetgirl.dataops.sync.dto.response.SeedResultResponse;
import sweetgirl.dataops.sync.dto.response.SyncStateResponse;
import sweetgirl.dataops.sync.enums.SyncStateStatus;
import sweetgirl.dataops.sync.enums.SyncTier;
import sweetgirl.dataops.sync.service.SyncSampleService;
import sweetgirl.dataops.sync.service.SyncSeedService;
import sweetgirl.dataops.sync.service.SyncStateCommandService;
import sweetgirl.dataops.sync.service.SyncStateQueryService;

@Tag(name = "SyncState", description = "가게×소스 동기화 상태 — due 목록이 곧 워커 워크리스트")
@RestController
@RequestMapping("/api/sync-states")
@RequiredArgsConstructor
public class SyncStateController {

	private final SyncStateCommandService syncStateCommandService;
	private final SyncStateQueryService syncStateQueryService;
	private final SyncSeedService syncSeedService;
	private final SyncSampleService syncSampleService;

	@Operation(summary = "due 목록 export", description = "워커 워크리스트 — 배부 시 last_attempt_at 스탬프(소프트 리스 2시간)")
	@GetMapping("/due")
	public ResponseEntity<ApiResponse<List<DueStoreResponse>>> exportDue(
			@RequestParam(defaultValue = "50") int limit,
			@RequestParam(required = false) List<Long> storeIds) {
		return ResponseEntity.ok(ApiResponse.ok(storeIds == null ? syncStateCommandService.exportDue(limit)
			: syncStateCommandService.exportSelectedDue(limit, storeIds)));
	}

	@Operation(summary = "fetch 실패 보고", description = "지수 백오프(1일→2→4…), 연속 10회면 UNLINKED 전환")
	@PostMapping("/{syncStateId}/failure")
	public ResponseEntity<ApiResponse<SyncStateResponse>> reportFailure(@PathVariable Long syncStateId) {
		return ResponseEntity.ok(ApiResponse.ok(syncStateCommandService.reportFailure(syncStateId)));
	}

	@Operation(summary = "상태·카운터 조회")
	@GetMapping
	public ResponseEntity<ApiResponse<PageResponse<SyncStateResponse>>> getSyncStates(
			@RequestParam(required = false) SyncTier tier,
			@RequestParam(required = false) SyncStateStatus state,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ResponseEntity.ok(ApiResponse.ok(syncStateQueryService.getSyncStates(tier, state, page, size)));
	}

	@Operation(summary = "최초 시딩 (멱등)", description = "운영 매칭 가게 → sync_state 벌크 생성 + 사용자 기여 메뉴 provenance USER 소급")
	@PostMapping("/seed")
	public ResponseEntity<ApiResponse<SeedResultResponse>> seed() {
		return ResponseEntity.ok(ApiResponse.ok(syncSeedService.seed()));
	}

	@Operation(summary = "관찰 표본 선정 (1단계)", description = "표본만 due 로 두고 나머지는 next_due_at NULL 보류. 메뉴 0개 80% + 메뉴 보유 20%, md5(storeId) 결정적 추출")
	@PostMapping("/observation-sample")
	public ResponseEntity<ApiResponse<ObservationSampleResponse>> selectObservationSample(
			@RequestParam(defaultValue = "1000") int size) {
		return ResponseEntity.ok(ApiResponse.ok(syncSampleService.selectObservationSample(size)));
	}

	@Operation(summary = "재크롤 영구 제외 (PARKED)")
	@PatchMapping("/{syncStateId}/park")
	public ResponseEntity<ApiResponse<SyncStateResponse>> park(@PathVariable Long syncStateId) {
		return ResponseEntity.ok(ApiResponse.ok(syncStateCommandService.park(syncStateId)));
	}

	@Operation(summary = "제외 해제 — 즉시 due (수동 즉시 재크롤 겸용)")
	@PatchMapping("/{syncStateId}/unpark")
	public ResponseEntity<ApiResponse<SyncStateResponse>> unpark(@PathVariable Long syncStateId) {
		return ResponseEntity.ok(ApiResponse.ok(syncStateCommandService.unpark(syncStateId)));
	}

	@Operation(summary = "티어 변경")
	@PatchMapping("/{syncStateId}/tier")
	public ResponseEntity<ApiResponse<SyncStateResponse>> changeTier(
			@PathVariable Long syncStateId,
			@RequestBody @Valid ChangeTierRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(syncStateCommandService.changeTier(syncStateId, request.tier())));
	}
}

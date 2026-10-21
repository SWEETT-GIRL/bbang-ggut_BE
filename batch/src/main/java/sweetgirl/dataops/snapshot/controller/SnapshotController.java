package sweetgirl.dataops.snapshot.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
import sweetgirl.dataops.reconcile.service.ReconcileService;
import sweetgirl.dataops.snapshot.dto.request.SnapshotSubmitRequest;
import sweetgirl.dataops.snapshot.dto.response.ParsedPlaceResponse;
import sweetgirl.dataops.snapshot.dto.response.SnapshotDetailResponse;
import sweetgirl.dataops.snapshot.dto.response.SnapshotResponse;
import sweetgirl.dataops.snapshot.dto.response.SnapshotSubmitResponse;
import sweetgirl.dataops.snapshot.service.SnapshotCommandService;
import sweetgirl.dataops.snapshot.service.SnapshotQueryService;

@Tag(name = "Snapshot", description = "원본층 — 워커가 제출한 파싱 전 원본 페이로드 (append-only)")
@RestController
@RequestMapping("/api/snapshots")
@RequiredArgsConstructor
public class SnapshotController {

	private final SnapshotCommandService snapshotCommandService;
	private final SnapshotQueryService snapshotQueryService;
	private final ReconcileService reconcileService;

	@Operation(summary = "워커 원본 제출", description = "SHA-256 해시가 직전과 동일하면 저장을 생략하고 방문 카운터만 올립니다 (changed=false)")
	@PostMapping
	public ResponseEntity<ApiResponse<SnapshotSubmitResponse>> submit(
			@RequestBody @Valid SnapshotSubmitRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.created(snapshotCommandService.submit(request)));
	}

	@Operation(summary = "스냅샷 이력 조회", description = "가게별 최신순 — payload 는 단건 조회로")
	@GetMapping
	public ResponseEntity<ApiResponse<PageResponse<SnapshotResponse>>> getSnapshots(
			@RequestParam Long storeId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ResponseEntity.ok(ApiResponse.ok(snapshotQueryService.getSnapshots(storeId, page, size)));
	}

	@Operation(summary = "스냅샷 단건 조회 (payload 포함)")
	@GetMapping("/{snapshotId}")
	public ResponseEntity<ApiResponse<SnapshotDetailResponse>> getSnapshot(@PathVariable Long snapshotId) {
		return ResponseEntity.ok(ApiResponse.ok(snapshotQueryService.getSnapshot(snapshotId)));
	}

	@Operation(summary = "파싱 미리보기", description = "저장된 원본을 실제 파서로 파싱한 결과 — 1단계 정합성 눈검사용")
	@GetMapping("/{snapshotId}/parsed")
	public ResponseEntity<ApiResponse<ParsedPlaceResponse>> getParsedSnapshot(@PathVariable Long snapshotId) {
		return ResponseEntity.ok(ApiResponse.ok(snapshotQueryService.getParsedSnapshot(snapshotId)));
	}

	@Operation(summary = "해시 재베이스라인", description = "해시 정책 변경 시 최신 스냅샷 기준으로 content_hash 재계산 (카운터 불변)")
	@PostMapping("/rebaseline")
	public ResponseEntity<ApiResponse<Integer>> rebaselineContentHashes() {
		return ResponseEntity.ok(ApiResponse.ok(snapshotCommandService.rebaselineContentHashes()));
	}

	@Operation(summary = "영업시간만 재판정", description = "영업시간 파서가 바뀌었을 때 지정한 가게의 최신 스냅샷으로 영업시간만 "
		+ "다시 판정해 제안을 만든다. 정리 범위는 영업시간 묶음뿐 — 다른 제안은 건드리지 않는다. 운영 쓰기 없음(제안은 PENDING)")
	@PostMapping("/reconcile-hours")
	public ResponseEntity<ApiResponse<ReconcileService.HoursRejudgeResult>> reconcileHours(
			@RequestBody java.util.List<Long> storeIds) {
		return ResponseEntity.ok(ApiResponse.ok(reconcileService.reconcileHoursFromSnapshots(storeIds)));
	}

	@Operation(summary = "재판정", description = "병합 정책(음료 필터 등) 변경 시 기존 PENDING 제안 삭제 후 저장된 스냅샷으로 재판정 (재크롤 불요)")
	@PostMapping("/reconcile-all")
	public ResponseEntity<ApiResponse<Integer>> reconcileAll() {
		return ResponseEntity.ok(ApiResponse.ok(reconcileService.reconcileAllFromSnapshots()));
	}
}

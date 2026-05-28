package sweetgirl.croissant.admin.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.dto.request.AdminNaverPlaceIdUpdateRequest;
import sweetgirl.croissant.admin.dto.request.AdminStoreCreateRequest;
import sweetgirl.croissant.admin.dto.request.AdminStoreUpdateRequest;
import sweetgirl.croissant.admin.dto.response.AdminNaverEnrichQueueItem;
import sweetgirl.croissant.admin.dto.response.AdminNaverEnrichRunResponse;
import sweetgirl.croissant.admin.dto.response.AdminNaverEnrichStatsResponse;
import sweetgirl.croissant.admin.dto.response.AdminStoreResponse;
import sweetgirl.croissant.admin.service.AdminStoreService;
import sweetgirl.croissant.admin.service.NaverEnrichOrchestrator;
import sweetgirl.croissant.admin.service.NaverEnrichRunner;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.store.code.StoreErrorCode;

@Tag(name = "Admin Store", description = "어드민 가게 관리 API")
@RestController
@RequestMapping("/api/admin/stores")
@RequiredArgsConstructor
public class AdminStoreController {

	private final AdminStoreService adminStoreService;
	private final NaverEnrichRunner naverEnrichRunner;
	private final NaverEnrichOrchestrator naverEnrichOrchestrator;

	@Operation(summary = "가게 등록", description = "어드민에서 새 가게를 등록합니다. "
		+ "coord(lat/lng)로 PostGIS Point를 생성하며, hours는 mon~sun 키로 영업시간을 설정합니다.")
	@PostMapping
	public ResponseEntity<ApiResponse<AdminStoreResponse>> createStore(
			@Valid @RequestBody AdminStoreCreateRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(adminStoreService.createStore(request)));
	}

	@Operation(summary = "네이버 enrich 큐 조회", description = "naverPlaceId 가 아직 매칭되지 않은 가게 목록을 페이지네이션으로 반환합니다. "
		+ "매칭 실패 후 7일 경과한 가게도 재시도 대상으로 포함됩니다. "
		+ "Python 크롤러가 이 엔드포인트를 폴링해 처리 대상을 받아갑니다.")
	@GetMapping("/naver-enrich-queue")
	public ResponseEntity<ApiResponse<Page<AdminNaverEnrichQueueItem>>> getNaverEnrichQueue(
			@Parameter(description = "페이지 번호 (0부터 시작)") @RequestParam(defaultValue = "0") int page,
			@Parameter(description = "페이지 크기") @RequestParam(defaultValue = "100") int size) {
		return ResponseEntity.ok(
			ApiResponse.ok(adminStoreService.getNaverEnrichQueue(PageRequest.of(page, size))));
	}

	@Operation(summary = "네이버 플레이스 ID 저장", description = "Python 크롤러가 네이버에서 매칭에 성공한 후 호출. "
		+ "naverPlaceId 와 naverMatchedAt 을 함께 갱신합니다.")
	@PutMapping("/{storeId}/naver-place-id")
	public ResponseEntity<ApiResponse<Void>> updateNaverPlaceId(
			@Parameter(description = "가게 ID") @PathVariable Long storeId,
			@Valid @RequestBody AdminNaverPlaceIdUpdateRequest request) {
		adminStoreService.updateNaverPlaceId(storeId, request.naverPlaceId());
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "네이버 enrich 실패 마킹", description = "Python 크롤러가 매칭에 실패한 후 호출. naverMatchedAt 만 기록되어 "
		+ "재시도 윈도우(7일)가 지나기 전까지는 큐에 다시 노출되지 않습니다.")
	@PostMapping("/{storeId}/naver-enrich-failed")
	public ResponseEntity<ApiResponse<Void>> markNaverEnrichFailed(
			@Parameter(description = "가게 ID") @PathVariable Long storeId) {
		adminStoreService.markNaverEnrichFailed(storeId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "네이버 enrich 통계", description = "ACTIVE store 기준 매칭/대기/실패/전체 카운트. 대시보드용.")
	@GetMapping("/naver-enrich-stats")
	public ResponseEntity<ApiResponse<AdminNaverEnrichStatsResponse>> getNaverEnrichStats() {
		return ResponseEntity.ok(ApiResponse.ok(adminStoreService.getNaverEnrichStats()));
	}

	@Operation(summary = "네이버 플레이스 ID 초기화", description = "잘못 매칭된 ID 를 비웁니다. "
		+ "naverPlaceId 와 naverMatchedAt 둘 다 null 로 변경되어 다음 크롤링 사이클에 큐에 다시 진입합니다.")
	@DeleteMapping("/{storeId}/naver-place-id")
	public ResponseEntity<ApiResponse<Void>> clearNaverPlaceId(
			@Parameter(description = "가게 ID") @PathVariable Long storeId) {
		adminStoreService.clearNaverPlaceId(storeId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "네이버 enrich 재시도 (throttle 해제)", description = "7일 throttle 을 강제 해제합니다. "
		+ "naverMatchedAt 만 null 로 바꿔서 큐에 즉시 노출됩니다. "
		+ "이미 naverPlaceId 가 있는 경우엔 효과 없음(큐 조건이 naverPlaceId IS NULL).")
	@PostMapping("/{storeId}/naver-enrich-retry")
	public ResponseEntity<ApiResponse<Void>> retryNaverEnrich(
			@Parameter(description = "가게 ID") @PathVariable Long storeId) {
		adminStoreService.retryNaverEnrich(storeId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "네이버 enrich 큐 아이템 (단일)", description = "단일 가게를 큐 아이템과 동일한 shape 으로 반환. "
		+ "Python 크롤러의 --store-id 모드에서 호출.")
	@GetMapping("/{storeId}/naver-enrich-item")
	public ResponseEntity<ApiResponse<AdminNaverEnrichQueueItem>> getNaverEnrichItem(
			@Parameter(description = "가게 ID") @PathVariable Long storeId) {
		return ResponseEntity.ok(ApiResponse.ok(adminStoreService.getNaverEnrichItem(storeId)));
	}

	@Operation(summary = "단일 가게 네이버 enrich 실행 (sync)", description = "Python 크롤러 subprocess 로 한 가게만 매칭 + 메뉴 보강. "
		+ "완료까지 sync 대기 (최대 180초). 어드민 웹의 행 단위 [Enrich] 버튼용.")
	@PostMapping("/{storeId}/naver-enrich-now")
	public ResponseEntity<ApiResponse<AdminNaverEnrichRunResponse>> runNaverEnrichForStore(
			@Parameter(description = "가게 ID") @PathVariable Long storeId) {
		return ResponseEntity.ok(ApiResponse.ok(naverEnrichRunner.runForStore(storeId)));
	}

	@Operation(summary = "네이버 enrich 일괄 시작 (async)", description = "Python 크롤러를 --loop 모드로 백그라운드 실행. 큐가 빌 때까지 처리. "
		+ "이미 실행 중이면 409. 진행상황은 /naver-enrich-stats 로 폴링.")
	@PostMapping("/naver-enrich-start")
	public ResponseEntity<ApiResponse<Void>> startBulkNaverEnrich() {
		if (!naverEnrichOrchestrator.tryStart()) {
			throw new CustomException(StoreErrorCode.NAVER_ENRICH_ALREADY_RUNNING);
		}
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "네이버 enrich 일괄 중단", description = "백그라운드 Python 프로세스에 SIGTERM 전송. 현재 처리 중인 가게는 끝까지 마치고 종료.")
	@PostMapping("/naver-enrich-stop")
	public ResponseEntity<ApiResponse<Void>> stopBulkNaverEnrich() {
		naverEnrichOrchestrator.requestStop();
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "가게 목록 조회", description = "가게 목록을 페이지네이션으로 조회합니다. "
		+ "keyword로 이름 검색, status로 활성/삭제 필터링이 가능합니다.")
	@GetMapping
	public ResponseEntity<ApiResponse<Page<AdminStoreResponse>>> getStores(
			@Parameter(description = "가게 이름 검색어 (부분 일치)") @RequestParam(required = false) String keyword,
			@Parameter(description = "가게 상태 (active | inactive)") @RequestParam(required = false) String status,
			@Parameter(description = "페이지 번호 (0부터 시작)") @RequestParam(defaultValue = "0") int page,
			@Parameter(description = "페이지 크기") @RequestParam(defaultValue = "20") int size) {
		return ResponseEntity.ok(
			ApiResponse.ok(adminStoreService.getStores(keyword, status, PageRequest.of(page, size))));
	}

	@Operation(summary = "가게 상세 조회", description = "가게 ID로 상세 정보를 조회합니다. 영업시간, 대표 사진, 구독 수(alarmCount)를 포함합니다.")
	@GetMapping("/{storeId}")
	public ResponseEntity<ApiResponse<AdminStoreResponse>> getStoreDetail(
			@Parameter(description = "가게 ID") @PathVariable Long storeId) {
		return ResponseEntity.ok(ApiResponse.ok(adminStoreService.getStoreDetail(storeId)));
	}

	@Operation(summary = "가게 수정", description = "가게 정보를 수정합니다. 모든 필드는 선택적이며, "
		+ "archivedAt이 non-null이면 상태가 DELETED로 변경됩니다.")
	@PutMapping("/{storeId}")
	public ResponseEntity<ApiResponse<Void>> updateStore(
			@Parameter(description = "가게 ID") @PathVariable Long storeId,
			@RequestBody AdminStoreUpdateRequest request) {
		adminStoreService.updateStore(storeId, request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "가게 삭제", description = "가게를 soft-delete합니다 (status → DELETED).")
	@DeleteMapping("/{storeId}")
	public ResponseEntity<ApiResponse<Void>> deleteStore(
			@Parameter(description = "가게 ID") @PathVariable Long storeId) {
		adminStoreService.deleteStore(storeId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}

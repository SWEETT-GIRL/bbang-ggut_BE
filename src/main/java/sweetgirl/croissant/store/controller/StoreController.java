package sweetgirl.croissant.store.controller;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.store.dto.request.KakaoStoreRegisterRequest;
import sweetgirl.croissant.store.dto.request.NaverStoreRegisterRequest;
import sweetgirl.croissant.store.dto.response.KakaoStoreRegisterResponse;
import sweetgirl.croissant.store.dto.response.NaverStoreRegisterResponse;
import sweetgirl.croissant.store.dto.response.NearbyStoreResponse;
import sweetgirl.croissant.store.dto.response.StoreAutocompleteResponse;
import sweetgirl.croissant.store.dto.response.StoreDetailResponse;
import sweetgirl.croissant.store.dto.response.StoreSearchResponse;
import sweetgirl.croissant.store.dto.response.SubscribedStoreResponse;
import sweetgirl.croissant.store.dto.response.TopSubscribedStoreResponse;
import sweetgirl.croissant.store.service.StoreCommandService;
import sweetgirl.croissant.store.service.StoreQueryService;
import sweetgirl.croissant.store.service.search.SearchSort;
import sweetgirl.croissant.store.service.search.StoreSearchService;

@Tag(name = "Store", description = "빵집 API")
@RestController
@RequestMapping("/stores")
@RequiredArgsConstructor
public class StoreController {

	private final StoreQueryService storeQueryService;
	private final StoreCommandService storeCommandService;
	private final StoreSearchService storeSearchService;

	@Operation(summary = "빵집 상세 조회", description = "빵집 ID로 상세 정보를 조회합니다.")
	@GetMapping("/{storeId}")
	public ResponseEntity<ApiResponse<StoreDetailResponse>> getStoreDetail(@AuthenticationPrincipal Long memberId,
			@PathVariable Long storeId) {
		return ResponseEntity.ok(ApiResponse.ok(storeQueryService.getStoreDetail(memberId, storeId)));
	}

	@Operation(summary = "알림받는 빵집 목록 조회", description = "사용자 ID로 알림받는 빵집 목록을 조회합니다.")
	@GetMapping("/subscribed")
	public ResponseEntity<ApiResponse<SliceResponse<SubscribedStoreResponse>>> getSubscribedStore(
			@AuthenticationPrincipal Long memberId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size) {
		return ResponseEntity
			.ok(ApiResponse.ok(storeQueryService.getSubscribedStores(memberId, PageRequest.of(page, size))));
	}

	@Operation(summary = "지역별 빵집 조회", description = "지역 키워드로 빵집 목록을 조회합니다. 거리순은 현재 위치(lat, lng)가 필요하고, 없으면 구독수순입니다.")
	@GetMapping("/district")
	public ResponseEntity<ApiResponse<SliceResponse<?>>> getStoresByDistrict(
			@Parameter(description = "지역 키워드 (예: 연남, 강남구, 부산)") @RequestParam String keyword,
			@RequestParam(required = false) Double lat,
			@RequestParam(required = false) Double lng,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "15") int size,
			@RequestParam(defaultValue = "distance") String sort) {
		// 좌표가 없으면 거리순 자체가 성립하지 않는다. lat/lng는 선택 파라미터인데
		// 거리순 서비스는 primitive double을 받아, 그대로 넘기면 언박싱 NPE로 500이 난다.
		// (위치 권한을 거부했거나 GPS를 아직 못 받은 클라이언트가 이 경로로 들어온다.)
		if ("subscribe".equals(sort) || lat == null || lng == null) {
			return ResponseEntity.ok(ApiResponse.ok(
				storeQueryService.getStoresByDistrictOrderBySubscribe(keyword, PageRequest.of(page, size))));
		}
		return ResponseEntity.ok(ApiResponse.ok(
			storeQueryService.getStoresByDistrictOrderByDistance(keyword, lat, lng, PageRequest.of(page, size))));
	}

	@Operation(summary = "빵집 검색", description = "OpenSearch 기반으로 빵집을 검색합니다.")
	@GetMapping("/search")
	public ResponseEntity<ApiResponse<SliceResponse<StoreSearchResponse>>> searchStores(
			@Parameter(description = "검색어") @RequestParam String query,
			@Parameter(description = "현재 위치 위도 — 정확도순은 거리 가중에, 거리순은 정렬에 사용") @RequestParam(required = false) Double lat,
			@Parameter(description = "현재 위치 경도 — 정확도순은 거리 가중에, 거리순은 정렬에 사용") @RequestParam(required = false) Double lon,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "15") int size,
			@Parameter(description = "정렬 (기본 정확도순)") @RequestParam(defaultValue = "relevance") String sort) {
		return ResponseEntity.ok(
			ApiResponse.ok(storeSearchService.searchStores(query, lat, lon, SearchSort.from(sort),
				PageRequest.of(page, size))));
	}

	@Operation(summary = "빵집 검색어 자동완성", description = "입력 중인 검색어를 기준으로 빵집·메뉴·빵 종류·지역 추천어를 조회합니다.")
	@GetMapping("/autocomplete")
	public ResponseEntity<ApiResponse<List<StoreAutocompleteResponse>>> autocompleteStores(
			@Parameter(description = "입력 중인 검색어") @RequestParam String query,
			@RequestParam(defaultValue = "10") int size) {
		return ResponseEntity.ok(ApiResponse.ok(storeSearchService.autocompleteStores(query, size)));
	}

	@Operation(summary = "근처 빵집 목록 조회", description = "위도(lat)·경도(lng) 기준 반경 내 빵집을 거리 오름차순으로 반환합니다.")
	@GetMapping("/nearby")
	public ResponseEntity<ApiResponse<SliceResponse<NearbyStoreResponse>>> getNearbyStore(
			@RequestParam double lat, @RequestParam double lng,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size) {
		return ResponseEntity.ok(
			ApiResponse.ok(storeQueryService.getNearbyStores(lng, lat, PageRequest.of(page, size))));
	}

	@Operation(summary = "인기 빵집 목록 조회", description = "구독 수 내림차순으로 상위 빵집 목록을 조회합니다.")
	@GetMapping("/popular")
	public ResponseEntity<ApiResponse<List<TopSubscribedStoreResponse>>> getTopSubscribedStores(
			@RequestParam double lat, @RequestParam double lng) {
		return ResponseEntity.ok(ApiResponse.ok(storeQueryService.getTopSubscribedStores(lng, lat)));
	}

	@Operation(summary = "카카오 검색 결과 빵집 등록", description = "카카오 검색 결과에서 클릭한 빵집을 DB에 저장하고 storeId를 반환")
	@PostMapping("/kakao")
	public ResponseEntity<ApiResponse<KakaoStoreRegisterResponse>> registerKakaoStore(
			@RequestBody KakaoStoreRegisterRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(storeCommandService.registerKakaoStore(request)));
	}

	@Operation(summary = "네이버 검색 결과 빵집 등록", description = "승인 없이 바로 저장하고 storeId를 반환")
	@PostMapping("/naver")
	public ResponseEntity<ApiResponse<NaverStoreRegisterResponse>> registerNaverStore(
			@RequestBody NaverStoreRegisterRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(storeCommandService.registerNaverStore(request)));
	}

	@Operation(summary = "빵집 구독 (빵알람 등록)", description = "빵집을 구독합니다. 이미 구독 중이면 409를 반환합니다.")
	@PostMapping("/{storeId}/subscribe")
	public ResponseEntity<ApiResponse<Void>> subscribeStore(
			@PathVariable Long storeId,
			@AuthenticationPrincipal Long memberId) {
		storeCommandService.subscribeStore(storeId, memberId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "빵집 구독 취소 (빵알람 해제)", description = "빵집 구독을 취소합니다. 구독 중이 아니면 400을 반환합니다.")
	@DeleteMapping("/{storeId}/subscribe")
	public ResponseEntity<ApiResponse<Void>> unsubscribeStore(
			@PathVariable Long storeId,
			@AuthenticationPrincipal Long memberId) {
		storeCommandService.unsubscribeStore(storeId, memberId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}

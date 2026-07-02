package sweetgirl.croissant.store.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.store.dto.request.StoreAddReportRequest;
import sweetgirl.croissant.store.dto.response.NaverPlaceSearchResult;
import sweetgirl.croissant.store.dto.response.StoreAddReportResponse;
import sweetgirl.croissant.store.service.StoreAddReportCommandService;
import sweetgirl.croissant.store.service.StoreAddReportQueryService;

@Tag(name = "StoreAddReport", description = "사용자 가게 추가 제보 API")
@RestController
@RequestMapping("/stores/add-reports")
@RequiredArgsConstructor
public class StoreAddReportController {

	private final StoreAddReportQueryService storeAddReportQueryService;
	private final StoreAddReportCommandService storeAddReportCommandService;

	@Operation(summary = "가게 추가용 네이버 장소 검색", description = "네이버 지역 검색 API 결과를 가게 추가 제보용으로 반환합니다.")
	@GetMapping("/naver/search")
	public ResponseEntity<ApiResponse<List<NaverPlaceSearchResult>>> searchNaverPlaces(
			@Parameter(description = "검색어") @RequestParam String keyword) {
		return ResponseEntity.ok(ApiResponse.ok(storeAddReportQueryService.searchNaverPlaces(keyword)));
	}

	@Operation(summary = "가게 추가 제보", description = "네이버 검색 결과에서 선택한 가게 정보를 기반으로 가게 추가 제보를 등록합니다.")
	@PostMapping
	public ResponseEntity<ApiResponse<StoreAddReportResponse>> createReport(
			@AuthenticationPrincipal Long memberId,
			@Valid @RequestBody StoreAddReportRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.created(storeAddReportCommandService.createReport(memberId, request)));
	}
}

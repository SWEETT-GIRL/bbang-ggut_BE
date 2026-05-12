package sweetgirl.croissant.store.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.store.dto.request.ReportPresignedUrlRequest;
import sweetgirl.croissant.store.dto.request.StoreAddressReportRequest;
import sweetgirl.croissant.store.dto.request.StoreBusinessHoursReportRequest;
import sweetgirl.croissant.store.dto.request.StoreImageReportRequest;
import sweetgirl.croissant.store.dto.response.AddressSearchResult;
import sweetgirl.croissant.store.dto.response.PresignedUrlInfoResponse;
import sweetgirl.croissant.store.dto.response.StoreReportResponse;
import sweetgirl.croissant.store.service.StoreReportService;

@Tag(name = "StoreReport", description = "가게 정보 수정 제보 API")
@RestController
@RequiredArgsConstructor
public class StoreReportController {

	private final StoreReportService storeReportService;

	@Operation(summary = "주소 검색", description = "카카오 주소 검색 API를 통해 주소를 검색합니다.")
	@GetMapping("/address/search")
	public ResponseEntity<ApiResponse<List<AddressSearchResult>>> searchAddress(
			@Parameter(description = "검색어") @RequestParam String searchKeyword) {
		return ResponseEntity.ok(ApiResponse.ok(storeReportService.searchAddress(searchKeyword)));
	}

	@Operation(summary = "가게 제보 이미지 Presigned URL 발급", description = "mimeTypes 배열 크기만큼 URL을 반환합니다.")
	@PostMapping("/stores/{storeId}/reports/images/presigned-urls")
	public ResponseEntity<ApiResponse<PresignedUrlInfoResponse>> getPresignedUrls(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long storeId,
			@Valid @RequestBody ReportPresignedUrlRequest request) {
		return ResponseEntity.ok(
			ApiResponse.ok(storeReportService.generateStoreReportPresignedUrls(memberId, storeId, request)));
	}

	@Operation(summary = "가게 제보 이미지 추가/삭제 요청", description = "")
	@PostMapping("/stores/{storeId}/reports/images")
	public ResponseEntity<ApiResponse<StoreReportResponse>> createImageReport(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long storeId,
			@Valid @RequestBody StoreImageReportRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.created(storeReportService.createImageReport(memberId, storeId, request)));
	}

	@Operation(summary = "가게 제보 - 영업시간 수정 요청", description = "")
	@PostMapping("/stores/{storeId}/reports/business-hours")
	public ResponseEntity<ApiResponse<StoreReportResponse>> createBusinessHoursReport(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long storeId,
			@Valid @RequestBody StoreBusinessHoursReportRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.created(storeReportService.createBusinessHoursReport(memberId, storeId, request)));
	}

	@Operation(summary = "가게 제보 - 주소 수정 요청", description = "")
	@PostMapping("/stores/{storeId}/reports/address")
	public ResponseEntity<ApiResponse<StoreReportResponse>> createAddressReport(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long storeId,
			@Valid @RequestBody StoreAddressReportRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.created(storeReportService.createAddressReport(memberId, storeId, request)));
	}
}

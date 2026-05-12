package sweetgirl.croissant.store.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.store.dto.request.ReportPresignedUrlRequest;
import sweetgirl.croissant.store.dto.request.StoreAddressReportRequest;
import sweetgirl.croissant.store.dto.request.StoreBusinessHoursReportRequest;
import sweetgirl.croissant.store.dto.request.StoreImageReportRequest;
import sweetgirl.croissant.store.dto.response.PresignedUrlInfoResponse;
import sweetgirl.croissant.store.dto.response.StoreReportResponse;
import sweetgirl.croissant.store.service.StoreReportService;

@Tag(name = "StoreReport", description = "가게 정보 수정 제보 API")
@RestController
@RequestMapping("/stores")
@RequiredArgsConstructor
public class StoreReportController {

	private final StoreReportService storeReportService;

	@Operation(summary = "가게 제보 이미지 Presigned URL 발급", description = "mimeTypes 배열 크기만큼 URL을 반환합니다.")
	@PostMapping("/{storeId}/reports/images/presigned-urls")
	public ResponseEntity<ApiResponse<PresignedUrlInfoResponse>> getPresignedUrls(
			@PathVariable Long storeId,
			@Valid @RequestBody ReportPresignedUrlRequest request) {

		return ResponseEntity.ok(
			ApiResponse.ok(storeReportService.generateStoreReportPresignedUrls(storeId, request)));
	}

	@Operation(summary = "가게 제보 이미지 추가/삭제 요청", description = "")
	@PostMapping("/{storeId}/reports/images")
	public ResponseEntity<ApiResponse<StoreReportResponse>> createImageReport(
			@PathVariable Long storeId,
			@Valid @RequestBody StoreImageReportRequest request) {
		return ResponseEntity.ok(
			ApiResponse.ok(null));
	}

	@Operation(summary = "가게 제보 - 영업시간 수정 요청", description = "")
	@PostMapping("/{storeId}/reports/business-hours")
	public ResponseEntity<ApiResponse<StoreReportResponse>> createBusinessHoursReport(
			@PathVariable Long storeId,
			@Valid @RequestBody StoreBusinessHoursReportRequest request) {
		return ResponseEntity.ok(
			ApiResponse.ok(null));
	}

	@Operation(summary = "가게 제보 - 주소 수정 요청", description = "")
	@PostMapping("/{storeId}/reports/address")
	public ResponseEntity<ApiResponse<StoreReportResponse>> createAddressReport(
			@PathVariable Long storeId,
			@Valid @RequestBody StoreAddressReportRequest request) {
		return ResponseEntity.ok(
			ApiResponse.ok(null));
	}

}

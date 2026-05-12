package sweetgirl.croissant.menu.controller;

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
import sweetgirl.croissant.store.dto.response.PresignedUrlInfoResponse;
import sweetgirl.croissant.store.service.StoreReportService;

@Tag(name = "Menu Report", description = "메뉴 정보 수정 제보 API")
@RequestMapping("/menus")
@RestController
@RequiredArgsConstructor
public class MenuReportController {

	private final StoreReportService storeReportService;

	@Operation(summary = "메뉴 수정 제보 이미지 Presigned URL 발급", description = "mimeTypes 배열 크기만큼 URL을 반환합니다.")
	@PostMapping("/{menuId}/reports/images/presigned-urls")
	public ResponseEntity<ApiResponse<PresignedUrlInfoResponse>> getPresignedUrls(
			@PathVariable Long menuId,
			@Valid @RequestBody ReportPresignedUrlRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(storeReportService.generateMenuReportPresignedUrls(menuId, request)));
	}
}

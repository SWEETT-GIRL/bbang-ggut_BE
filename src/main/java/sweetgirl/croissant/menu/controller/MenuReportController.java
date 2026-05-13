package sweetgirl.croissant.menu.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import sweetgirl.croissant.menu.dto.request.MenuEditReportRequest;
import sweetgirl.croissant.store.dto.request.ReportPresignedUrlRequest;
import sweetgirl.croissant.store.dto.response.PresignedUrlInfoResponse;
import sweetgirl.croissant.store.dto.response.StoreReportResponse;
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
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long menuId,
			@Valid @RequestBody ReportPresignedUrlRequest request) {
		return ResponseEntity
			.ok(ApiResponse.ok(storeReportService.generateMenuReportPresignedUrls(memberId, menuId, request)));
	}

	@Operation(summary = "메뉴 수정 제보 - 가격·사진 변경 요청", description = "메뉴 가격 또는 사진 변경 제보를 등록합니다."
		+ " 이미지는 Presigned URL로 업로드 후 imageKeys 배열에 담아 전달하세요. 201 반환.")
	@PostMapping("/{menuId}/reports/edit")
	public ResponseEntity<ApiResponse<StoreReportResponse>> createMenuEditReport(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long menuId,
			@Valid @RequestBody MenuEditReportRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.created(storeReportService.createMenuEditReport(memberId, menuId, request)));
	}
}

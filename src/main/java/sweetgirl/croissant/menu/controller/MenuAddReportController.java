package sweetgirl.croissant.menu.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.menu.dto.request.MenuAddReportRequest;
import sweetgirl.croissant.menu.service.MenuAddReportCommandService;

@Tag(name = "MenuAddReport", description = "사용자 메뉴 추가 요청 API")
@RestController
@RequiredArgsConstructor
public class MenuAddReportController {

	private final MenuAddReportCommandService menuAddReportCommandService;

	@Operation(summary = "메뉴 추가 요청 이미지 Presigned URL 발급", description = "S3 업로드용 Presigned URL을 발급합니다.")
	@PostMapping("/menu-add-reports/images/presigned-url")
	public ResponseEntity<ApiResponse<PresignedUrlResponse>> getPresignedUrl(
			@RequestParam String mimeType) {
		return ResponseEntity.ok(ApiResponse.ok(menuAddReportCommandService.getPresignedUrl(mimeType)));
	}

	@Operation(summary = "메뉴 추가 요청", description = "S3 업로드 완료 후 이미지 키를 포함해 메뉴 추가를 요청합니다.")
	@PostMapping("/stores/{storeId}/menu-add-reports")
	public ResponseEntity<ApiResponse<Void>> createMenuAddReport(
			@PathVariable Long storeId,
			@AuthenticationPrincipal Long memberId,
			@RequestBody @Valid MenuAddReportRequest request) {
		menuAddReportCommandService.createMenuAddReport(storeId, memberId, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(null));
	}
}

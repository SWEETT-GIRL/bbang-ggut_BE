package sweetgirl.croissant.banner.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
import sweetgirl.croissant.banner.dto.request.BannerCreateRequest;
import sweetgirl.croissant.banner.dto.request.BannerReorderRequest;
import sweetgirl.croissant.banner.service.AdminBannerCommandService;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Admin Banner", description = "어드민 배너 관리 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/banners")
public class AdminBannerController {

	private final AdminBannerCommandService adminBannerCommandService;

	@Operation(summary = "배너 이미지 Presigned URL 발급", description = "S3 업로드용 Presigned URL을 발급합니다. 썸네일/상세 각각 호출하세요.")
	@PostMapping("/presigned-url")
	public ResponseEntity<PresignedUrlResponse> getPresignedUrl(
			@Parameter(description = "MIME 타입 (image/jpeg, image/png 등)") @RequestParam String mimeType) {
		return ResponseEntity.ok(adminBannerCommandService.getPresignedUrl(mimeType));
	}

	@Operation(summary = "배너 추가", description = "썸네일 및 상세 이미지 S3 키를 저장합니다.")
	@PostMapping
	public ResponseEntity<ApiResponse<Void>> createBanner(@RequestBody @Valid BannerCreateRequest request) {
		adminBannerCommandService.createBanner(request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "배너 삭제", description = "배너를 S3와 DB에서 함께 삭제합니다.")
	@DeleteMapping("/{bannerId}")
	public ResponseEntity<ApiResponse<Void>> deleteBanner(
			@Parameter(description = "배너 ID") @PathVariable Long bannerId) {
		adminBannerCommandService.deleteBanner(bannerId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "배너 순서 변경", description = "배너 ID 목록을 원하는 순서대로 전달하면 displayOrder를 업데이트합니다.")
	@PatchMapping("/reorder")
	public ResponseEntity<ApiResponse<Void>> reorderBanners(@RequestBody @Valid BannerReorderRequest request) {
		adminBannerCommandService.reorderBanners(request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}

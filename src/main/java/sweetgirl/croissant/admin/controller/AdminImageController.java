package sweetgirl.croissant.admin.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
import sweetgirl.croissant.admin.dto.response.AdminS3ReconcileResponse;
import sweetgirl.croissant.admin.service.AdminImageService;
import sweetgirl.croissant.admin.service.AdminS3ReconcileService;
import sweetgirl.croissant.external.aws.dto.AdminImageResponse;
import sweetgirl.croissant.external.aws.dto.ImageOrderUpdateRequest;
import sweetgirl.croissant.external.aws.dto.ImageSaveRequest;
import sweetgirl.croissant.external.aws.dto.ImageUploadFromUrlRequest;
import sweetgirl.croissant.external.aws.dto.ImageUploadFromUrlResponse;
import sweetgirl.croissant.external.aws.dto.ImageUploadRequest;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Admin Image", description = "어드민 이미지 관리 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/images")
public class AdminImageController {

	private static final String RECONCILE_DESCRIPTION = "DB awsKey 에 없는 S3 객체와 S3 에 없는 DB DONE 이미지를 식별합니다. "
		+ "dryRun=false 면 orphan S3 객체는 삭제하고 missing DB 이미지는 FAILED 로 숨깁니다.";

	private final AdminImageService adminImageService;
	private final AdminS3ReconcileService adminS3ReconcileService;

	@Operation(summary = "Presigned URL 발급", description = "S3 업로드용 Presigned URL을 발급합니다.")
	@PostMapping("/presigned-url")
	public ResponseEntity<PresignedUrlResponse> getPresignedUrl(
			@RequestBody @Valid ImageUploadRequest request) {
		return ResponseEntity.ok(adminImageService.getPresignedUrl(request));
	}

	@Operation(summary = "가게 이미지 목록 조회", description = "가게에 등록된 이미지 목록을 반환합니다.")
	@GetMapping("/stores/{storeId}")
	public ResponseEntity<ApiResponse<List<AdminImageResponse>>> getStoreImages(
			@Parameter(description = "가게 ID") @PathVariable Long storeId) {
		return ResponseEntity.ok(ApiResponse.ok(adminImageService.getStoreImages(storeId)));
	}

	@Operation(summary = "가게 이미지 DB 저장", description = "S3 업로드 완료 후 이미지 정보를 DB에 저장합니다. 여러 장 한 번에 가능합니다.")
	@PostMapping("/stores/{storeId}")
	public ResponseEntity<Void> saveStoreImages(
			@Parameter(description = "가게 ID") @PathVariable Long storeId,
			@RequestBody List<ImageSaveRequest> requests) {
		adminImageService.saveStoreImages(storeId, requests);
		return ResponseEntity.ok().build();
	}

	@Operation(summary = "가게 이미지 순서 수정", description = "가게 이미지들의 displayOrder를 일괄 업데이트합니다.")
	@PatchMapping("/stores/{storeId}/order")
	public ResponseEntity<ApiResponse<Void>> reorderStoreImages(
			@Parameter(description = "가게 ID") @PathVariable Long storeId,
			@RequestBody @Valid ImageOrderUpdateRequest request) {
		adminImageService.reorderStoreImages(storeId, request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "가게 이미지 삭제", description = "가게 이미지를 S3와 DB에서 함께 삭제합니다.")
	@DeleteMapping("/stores/{storeId}/images/{imageId}")
	public ResponseEntity<ApiResponse<Void>> deleteStoreImage(
			@Parameter(description = "가게 ID") @PathVariable Long storeId,
			@Parameter(description = "이미지 ID") @PathVariable Long imageId) {
		adminImageService.deleteStoreImage(storeId, imageId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "메뉴 이미지 목록 조회", description = "메뉴에 등록된 이미지 목록을 반환합니다.")
	@GetMapping("/menus/{menuId}")
	public ResponseEntity<ApiResponse<List<AdminImageResponse>>> getMenuImages(
			@Parameter(description = "메뉴 ID") @PathVariable Long menuId) {
		return ResponseEntity.ok(ApiResponse.ok(adminImageService.getMenuImages(menuId)));
	}

	@Operation(summary = "메뉴 이미지 DB 저장", description = "S3 업로드 완료 후 이미지 정보를 DB에 저장합니다. 여러 장 한 번에 가능합니다.")
	@PostMapping("/menus/{menuId}")
	public ResponseEntity<Void> saveMenuImages(
			@Parameter(description = "메뉴 ID") @PathVariable Long menuId,
			@RequestBody List<ImageSaveRequest> requests) {
		adminImageService.saveMenuImages(menuId, requests);
		return ResponseEntity.ok().build();
	}

	@Operation(summary = "메뉴 이미지 순서 수정", description = "메뉴 이미지들의 displayOrder를 일괄 업데이트합니다.")
	@PatchMapping("/menus/{menuId}/order")
	public ResponseEntity<ApiResponse<Void>> reorderMenuImages(
			@Parameter(description = "메뉴 ID") @PathVariable Long menuId,
			@RequestBody @Valid ImageOrderUpdateRequest request) {
		adminImageService.reorderMenuImages(menuId, request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "메뉴 이미지 삭제", description = "메뉴 이미지를 S3와 DB에서 함께 삭제합니다.")
	@DeleteMapping("/menus/{menuId}/images/{imageId}")
	public ResponseEntity<ApiResponse<Void>> deleteMenuImage(
			@Parameter(description = "메뉴 ID") @PathVariable Long menuId,
			@Parameter(description = "이미지 ID") @PathVariable Long imageId) {
		adminImageService.deleteMenuImage(menuId, imageId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "S3 ↔ DB 정합성 검사 및 orphan/missing 정리", description = RECONCILE_DESCRIPTION)
	@PostMapping("/reconcile")
	public ResponseEntity<ApiResponse<AdminS3ReconcileResponse>> reconcileS3(
			@RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun) {
		return ResponseEntity.ok(ApiResponse.ok(adminS3ReconcileService.reconcile(dryRun)));
	}

	@Operation(summary = "URL에서 이미지 일괄 업로드", description = "외부 URL 리스트를 받아 백엔드가 다운로드 → S3 업로드 → DB 저장을 한 번에 처리합니다. "
		+ "Python 스크래퍼 등 외부 호출자의 S3 orphan 위험을 차단합니다. per-URL 성공/실패 결과를 반환합니다.")
	@PostMapping("/{domain}/{targetId}/from-url")
	public ResponseEntity<ApiResponse<ImageUploadFromUrlResponse>> uploadImagesFromUrls(
			@Parameter(description = "도메인 (stores 또는 menus)") @PathVariable String domain,
			@Parameter(description = "가게 또는 메뉴 ID") @PathVariable Long targetId,
			@RequestBody @Valid List<ImageUploadFromUrlRequest> requests) {
		return ResponseEntity.ok(
			ApiResponse.ok(adminImageService.uploadImagesFromUrls(domain, targetId, requests)));
	}
}

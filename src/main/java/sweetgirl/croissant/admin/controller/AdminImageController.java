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
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.service.AdminImageService;
import sweetgirl.croissant.external.aws.dto.AdminImageResponse;
import sweetgirl.croissant.external.aws.dto.ImageOrderUpdateRequest;
import sweetgirl.croissant.external.aws.dto.ImageSaveRequest;
import sweetgirl.croissant.external.aws.dto.ImageUploadRequest;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Admin Image", description = "어드민 이미지 관리 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/images")
public class AdminImageController {

	private final AdminImageService adminImageService;

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
}

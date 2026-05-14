package sweetgirl.croissant.admin.cotroller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.service.AdminImageService;
import sweetgirl.croissant.external.aws.dto.AdminImageResponse;
import sweetgirl.croissant.external.aws.dto.ImageSaveRequest;
import sweetgirl.croissant.external.aws.dto.ImageUploadRequest;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
import sweetgirl.croissant.global.response.ApiResponse;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/images")
public class AdminImageController {

	private final AdminImageService adminImageService;

	@PostMapping("/presigned-url")
	public ResponseEntity<PresignedUrlResponse> getPresignedUrl(
			@RequestBody @Valid ImageUploadRequest request) {
		return ResponseEntity.ok(adminImageService.getPresignedUrl(request));
	}

	@GetMapping("/stores/{storeId}")
	public ResponseEntity<ApiResponse<List<AdminImageResponse>>> getStoreImages(
			@PathVariable Long storeId) {
		return ResponseEntity.ok(ApiResponse.ok(adminImageService.getStoreImages(storeId)));
	}

	@PostMapping("/stores/{storeId}")
	public ResponseEntity<Void> saveStoreImages(
			@PathVariable Long storeId,
			@RequestBody List<ImageSaveRequest> requests) {
		adminImageService.saveStoreImages(storeId, requests);
		return ResponseEntity.ok().build();
	}

	@DeleteMapping("/stores/{storeId}/images/{imageId}")
	public ResponseEntity<ApiResponse<Void>> deleteStoreImage(
			@PathVariable Long storeId,
			@PathVariable Long imageId) {
		adminImageService.deleteStoreImage(storeId, imageId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@GetMapping("/menus/{menuId}")
	public ResponseEntity<ApiResponse<List<AdminImageResponse>>> getMenuImages(
			@PathVariable Long menuId) {
		return ResponseEntity.ok(ApiResponse.ok(adminImageService.getMenuImages(menuId)));
	}

	@PostMapping("/menus/{menuId}")
	public ResponseEntity<Void> saveMenuImages(
			@PathVariable Long menuId,
			@RequestBody List<ImageSaveRequest> requests) {
		adminImageService.saveMenuImages(menuId, requests);
		return ResponseEntity.ok().build();
	}

	@DeleteMapping("/menus/{menuId}/images/{imageId}")
	public ResponseEntity<ApiResponse<Void>> deleteMenuImage(
			@PathVariable Long menuId,
			@PathVariable Long imageId) {
		adminImageService.deleteMenuImage(menuId, imageId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}

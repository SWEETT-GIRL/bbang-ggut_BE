package sweetgirl.croissant.admin.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.dto.AdminImageResponse;
import sweetgirl.croissant.external.aws.dto.ImageSaveRequest;
import sweetgirl.croissant.external.aws.dto.ImageUploadRequest;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
import sweetgirl.croissant.external.aws.enums.MimeType;
import sweetgirl.croissant.external.aws.exception.S3ErrorCode;
import sweetgirl.croissant.external.aws.exception.S3StorageException;
import sweetgirl.croissant.external.aws.util.S3PathGenerator;
import sweetgirl.croissant.menu.code.MenuErrorCode;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuImageFile;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreImageFile;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminImageService {

	private final S3Service s3Service;
	private final StoreRepository storeRepository;
	private final MenuRepository menuRepository;
	private final StoreImageFileRepository storeImageFileRepository;
	private final MenuImageFileRepository menuImageFileRepository;

	public PresignedUrlResponse getPresignedUrl(ImageUploadRequest request) {
		String path;
		if ("stores".equalsIgnoreCase(request.getDomain())) {
			path = S3PathGenerator.getStorePath(request.getTargetId());
		} else if ("menus".equalsIgnoreCase(request.getDomain())) {
			path = S3PathGenerator.getMenuPath(request.getTargetId());
		} else {
			throw new IllegalArgumentException("지원하지 않는 도메인입니다.");
		}

		return s3Service.generatePresignedUrl(path, request.getMimeType());
	}

	public List<AdminImageResponse> getStoreImages(Long storeId) {
		if (!storeRepository.existsById(storeId)) {
			throw new StoreException(StoreErrorCode.STORE_NOT_FOUND);
		}
		return storeImageFileRepository.findByStoreId(storeId).stream()
			.map(img -> new AdminImageResponse(img.getId(), img.getAwsKey(), img.getMimeType().getValue(),
				img.getCreatedAt()))
			.collect(Collectors.toList());
	}

	public List<AdminImageResponse> getMenuImages(Long menuId) {
		if (!menuRepository.existsById(menuId)) {
			throw new MenuException(MenuErrorCode.MENU_NOT_FOUND);
		}
		return menuImageFileRepository.findImagesByMenuId(menuId).stream()
			.map(img -> new AdminImageResponse(img.getId(), img.getAwsKey(), img.getMimeType().getValue(),
				img.getCreatedAt()))
			.collect(Collectors.toList());
	}

	@Transactional
	public void saveStoreImages(Long storeId, List<ImageSaveRequest> requests) {
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));

		List<StoreImageFile> imageFiles = requests.stream()
			.map(req -> StoreImageFile.builder()
				.store(store)
				.name(req.getFileName())
				.mimeType(MimeType.fromString(req.getMimeType()))
				.awsKey(req.getAwsKey())
				.build())
			.collect(Collectors.toList());

		storeImageFileRepository.saveAll(imageFiles);
	}

	@Transactional
	public void saveMenuImages(Long menuId, List<ImageSaveRequest> requests) {
		Menu menu = menuRepository.findById(menuId)
			.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_NOT_FOUND));

		List<MenuImageFile> imageFiles = requests.stream()
			.map(req -> MenuImageFile.builder()
				.menu(menu)
				.name(req.getFileName())
				.mimeType(MimeType.fromString(req.getMimeType()))
				.awsKey(req.getAwsKey())
				.build())
			.collect(Collectors.toList());

		menuImageFileRepository.saveAll(imageFiles);
	}

	@Transactional
	public void deleteStoreImage(Long storeId, Long imageId) {
		StoreImageFile image = storeImageFileRepository.findById(imageId)
			.filter(img -> img.getStore().getId().equals(storeId))
			.orElseThrow(() -> new S3StorageException(S3ErrorCode.IMAGE_NOT_FOUND));

		s3Service.deleteObject(image.getAwsKey());
		storeImageFileRepository.delete(image);
	}

	@Transactional
	public void deleteMenuImage(Long menuId, Long imageId) {
		MenuImageFile image = menuImageFileRepository.findById(imageId)
			.filter(img -> img.getMenu().getId().equals(menuId))
			.orElseThrow(() -> new S3StorageException(S3ErrorCode.IMAGE_NOT_FOUND));

		s3Service.deleteObject(image.getAwsKey());
		menuImageFileRepository.delete(image);
	}
}

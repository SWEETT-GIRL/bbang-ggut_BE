package sweetgirl.croissant.admin.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.dto.AdminImageResponse;
import sweetgirl.croissant.external.aws.dto.ImageOrderUpdateRequest;
import sweetgirl.croissant.external.aws.dto.ImageSaveRequest;
import sweetgirl.croissant.external.aws.dto.ImageUploadFromUrlRequest;
import sweetgirl.croissant.external.aws.dto.ImageUploadFromUrlResponse;
import sweetgirl.croissant.external.aws.dto.ImageUploadRequest;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
import sweetgirl.croissant.external.aws.exception.S3ErrorCode;
import sweetgirl.croissant.external.aws.exception.S3StorageException;
import sweetgirl.croissant.external.aws.util.ImageDownloader;
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

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminImageService {

	private static final String DOMAIN_STORES = "stores";
	private static final String DOMAIN_MENUS = "menus";

	private final S3Service s3Service;
	private final StoreRepository storeRepository;
	private final MenuRepository menuRepository;
	private final StoreImageFileRepository storeImageFileRepository;
	private final MenuImageFileRepository menuImageFileRepository;
	private final ImageDownloader imageDownloader;
	private final ImageRowSaver imageRowSaver;

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
		return storeImageFileRepository.findByStoreIdOrderByDisplayOrderAscIdAsc(storeId).stream()
			.map(img -> new AdminImageResponse(img.getId(), img.getAwsKey(), img.getMimeType().getValue(),
				img.getDisplayOrder(), img.getCreatedAt(), img.getResizedKey640()))
			.collect(Collectors.toList());
	}

	public List<AdminImageResponse> getMenuImages(Long menuId) {
		if (!menuRepository.existsById(menuId)) {
			throw new MenuException(MenuErrorCode.MENU_NOT_FOUND);
		}
		return menuImageFileRepository.findImagesByMenuId(menuId).stream()
			.map(img -> new AdminImageResponse(img.getId(), img.getAwsKey(), img.getMimeType().getValue(),
				img.getDisplayOrder(), img.getCreatedAt(), img.getResizedKey640()))
			.collect(Collectors.toList());
	}

	@Transactional
	public void saveStoreImages(Long storeId, List<ImageSaveRequest> requests) {
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));

		// displayOrder 가 null 인 경우 (자동 크롤러 등) 기존 최대값 + 1 부터 순차 부여.
		int nextOrder = storeImageFileRepository.findMaxDisplayOrderByStoreId(storeId) + 1;
		List<StoreImageFile> imageFiles = new ArrayList<>();
		for (ImageSaveRequest req : requests) {
			int order = req.getDisplayOrder() != null ? req.getDisplayOrder() : nextOrder++;
			imageFiles.add(StoreImageFile.builder()
				.store(store)
				.name(req.getFileName())
				.mimeType(MimeType.fromString(req.getMimeType()))
				.awsKey(req.getAwsKey())
				.displayOrder(order)
				.build());
		}
		storeImageFileRepository.saveAll(imageFiles);
	}

	@Transactional
	public void saveMenuImages(Long menuId, List<ImageSaveRequest> requests) {
		Menu menu = menuRepository.findById(menuId)
			.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_NOT_FOUND));

		int nextOrder = menuImageFileRepository.findMaxDisplayOrderByMenuId(menuId) + 1;
		List<MenuImageFile> imageFiles = new ArrayList<>();
		for (ImageSaveRequest req : requests) {
			int order = req.getDisplayOrder() != null ? req.getDisplayOrder() : nextOrder++;
			imageFiles.add(MenuImageFile.builder()
				.menu(menu)
				.name(req.getFileName())
				.mimeType(MimeType.fromString(req.getMimeType()))
				.awsKey(req.getAwsKey())
				.displayOrder(order)
				.build());
		}
		menuImageFileRepository.saveAll(imageFiles);
	}

	@Transactional
	public void reorderStoreImages(Long storeId, ImageOrderUpdateRequest request) {
		if (!storeRepository.existsById(storeId)) {
			throw new StoreException(StoreErrorCode.STORE_NOT_FOUND);
		}
		List<Long> imageIds = request.getOrders().stream()
			.map(ImageOrderUpdateRequest.ImageOrder::getId)
			.toList();
		if (storeImageFileRepository.countByIdInAndStoreId(imageIds, storeId) != imageIds.size()) {
			throw new S3StorageException(S3ErrorCode.IMAGE_NOT_FOUND);
		}
		Map<Long, Integer> orderMap = request.getOrders().stream()
			.collect(Collectors.toMap(
				ImageOrderUpdateRequest.ImageOrder::getId,
				ImageOrderUpdateRequest.ImageOrder::getDisplayOrder));
		storeImageFileRepository.findByIdInAndStoreId(imageIds, storeId)
			.forEach(img -> img.updateDisplayOrder(orderMap.get(img.getId())));
	}

	@Transactional
	public void reorderMenuImages(Long menuId, ImageOrderUpdateRequest request) {
		if (!menuRepository.existsById(menuId)) {
			throw new MenuException(MenuErrorCode.MENU_NOT_FOUND);
		}
		List<Long> imageIds = request.getOrders().stream()
			.map(ImageOrderUpdateRequest.ImageOrder::getId)
			.toList();
		if (menuImageFileRepository.countByIdInAndMenuId(imageIds, menuId) != imageIds.size()) {
			throw new S3StorageException(S3ErrorCode.IMAGE_NOT_FOUND);
		}
		Map<Long, Integer> orderMap = request.getOrders().stream()
			.collect(Collectors.toMap(
				ImageOrderUpdateRequest.ImageOrder::getId,
				ImageOrderUpdateRequest.ImageOrder::getDisplayOrder));
		menuImageFileRepository.findByIdInAndMenuId(imageIds, menuId)
			.forEach(img -> img.updateDisplayOrder(orderMap.get(img.getId())));
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

	// 백엔드가 download → S3 → DB 를 모두 처리해 Python 스크래퍼의 S3 orphan 위험을 차단.
	// 한 URL 실패가 다른 URL 처리를 막지 않도록 per-URL 격리.
	public ImageUploadFromUrlResponse uploadImagesFromUrls(
			String domain, Long targetId, List<ImageUploadFromUrlRequest> requests) {
		String path = resolvePath(domain, targetId);
		assertTargetExists(domain, targetId);

		int nextOrder = nextDisplayOrder(domain, targetId);
		List<ImageUploadFromUrlResponse.Item> results = new ArrayList<>();
		int successCount = 0;
		int failureCount = 0;

		for (ImageUploadFromUrlRequest req : requests) {
			int displayOrder = req.getDisplayOrder() != null ? req.getDisplayOrder() : nextOrder++;
			ImageUploadFromUrlResponse.Item item = uploadOne(domain, targetId, path, req.getUrl(), displayOrder);
			results.add(item);
			ImageUploadFromUrlResponse.Status status = item.status();
			if (status == ImageUploadFromUrlResponse.Status.SUCCESS
				|| status == ImageUploadFromUrlResponse.Status.SKIPPED) {
				successCount++;
			} else {
				failureCount++;
			}
		}
		return new ImageUploadFromUrlResponse(results, successCount, failureCount);
	}

	private ImageUploadFromUrlResponse.Item uploadOne(
			String domain, Long targetId, String path, String url, int displayOrder) {
		if (url == null || url.isBlank()) {
			return ImageUploadFromUrlResponse.Item.failure(
				url, ImageUploadFromUrlResponse.Status.INVALID_URL, "blank url");
		}

		// Idempotency — 같은 (target, source_url) 이미 저장돼 있으면 다운로드 자체를 스킵.
		ImageUploadFromUrlResponse.Item existing = findExistingBySourceUrl(domain, targetId, url);
		if (existing != null) {
			return existing;
		}

		ImageDownloader.Result downloaded = imageDownloader.download(url);
		if (!downloaded.isSuccess()) {
			log.warn("[uploadOne] download fail url={} reason={}", url, downloaded.failureReason());
			return ImageUploadFromUrlResponse.Item.failure(
				url, ImageUploadFromUrlResponse.Status.DOWNLOAD_FAILED, downloaded.failureReason());
		}

		String fileName = UUID.randomUUID().toString();
		String awsKey = path + "/" + fileName;
		try {
			s3Service.putObject(awsKey, downloaded.bytes(), downloaded.mimeType());
		} catch (RuntimeException e) {
			log.warn("[uploadOne] s3 put fail url={} key={} reason={}", url, awsKey, e.getMessage());
			return ImageUploadFromUrlResponse.Item.failure(
				url, ImageUploadFromUrlResponse.Status.UPLOAD_FAILED, e.getMessage());
		}

		try {
			Long imageId = DOMAIN_STORES.equals(domain)
				? imageRowSaver.saveStoreImage(targetId, fileName, awsKey, downloaded.mimeType(), displayOrder, url)
				: imageRowSaver.saveMenuImage(targetId, fileName, awsKey, downloaded.mimeType(), displayOrder, url);
			return ImageUploadFromUrlResponse.Item.success(url, imageId, awsKey);
		} catch (DataIntegrityViolationException e) {
			// UNIQUE(target, source_url) 위반 — 동시 크롤이 같은 이미지를 먼저 저장함. 방금 올린 S3 는
			// orphan 이므로 정리하고, 기존 행을 찾아 SKIPPED 로 반환(중복 행 생성 방지).
			log.info("[uploadOne] duplicate source_url (race) url={} — skip", url);
			try {
				s3Service.deleteObject(awsKey);
			} catch (RuntimeException cleanupError) {
				log.error("[uploadOne] orphan in s3 key={} cleanup failed: {}", awsKey, cleanupError.getMessage());
			}
			ImageUploadFromUrlResponse.Item raced = findExistingBySourceUrl(domain, targetId, url);
			return raced != null ? raced
				: ImageUploadFromUrlResponse.Item.failure(
					url, ImageUploadFromUrlResponse.Status.DB_FAILED, "duplicate source_url");
		} catch (RuntimeException e) {
			log.warn("[uploadOne] db save fail url={} key={} reason={}", url, awsKey, e.getMessage());
			try {
				s3Service.deleteObject(awsKey);
			} catch (RuntimeException cleanupError) {
				log.error("[uploadOne] orphan in s3 key={} cleanup failed: {}", awsKey, cleanupError.getMessage());
			}
			return ImageUploadFromUrlResponse.Item.failure(
				url, ImageUploadFromUrlResponse.Status.DB_FAILED, e.getMessage());
		}
	}

	private ImageUploadFromUrlResponse.Item findExistingBySourceUrl(String domain, Long targetId, String url) {
		if (DOMAIN_STORES.equalsIgnoreCase(domain)) {
			return storeImageFileRepository.findByStoreIdAndSourceUrl(targetId, url)
				.map(img -> ImageUploadFromUrlResponse.Item.skipped(url, img.getId(), img.getAwsKey()))
				.orElse(null);
		}
		return menuImageFileRepository.findByMenuIdAndSourceUrl(targetId, url)
			.map(img -> ImageUploadFromUrlResponse.Item.skipped(url, img.getId(), img.getAwsKey()))
			.orElse(null);
	}

	private String resolvePath(String domain, Long targetId) {
		if (DOMAIN_STORES.equalsIgnoreCase(domain)) {
			return S3PathGenerator.getStorePath(targetId);
		}
		if (DOMAIN_MENUS.equalsIgnoreCase(domain)) {
			return S3PathGenerator.getMenuPath(targetId);
		}
		throw new IllegalArgumentException("지원하지 않는 도메인입니다: " + domain);
	}

	private void assertTargetExists(String domain, Long targetId) {
		if (DOMAIN_STORES.equalsIgnoreCase(domain)) {
			if (!storeRepository.existsById(targetId)) {
				throw new StoreException(StoreErrorCode.STORE_NOT_FOUND);
			}
		} else if (DOMAIN_MENUS.equalsIgnoreCase(domain)) {
			if (!menuRepository.existsById(targetId)) {
				throw new MenuException(MenuErrorCode.MENU_NOT_FOUND);
			}
		}
	}

	private int nextDisplayOrder(String domain, Long targetId) {
		if (DOMAIN_STORES.equalsIgnoreCase(domain)) {
			return storeImageFileRepository.findMaxDisplayOrderByStoreId(targetId) + 1;
		}
		return menuImageFileRepository.findMaxDisplayOrderByMenuId(targetId) + 1;
	}
}

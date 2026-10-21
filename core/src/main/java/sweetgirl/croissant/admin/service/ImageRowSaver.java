package sweetgirl.croissant.admin.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.external.aws.service.ImageProcessingCallbackService;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuImageFile;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreImageFile;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreRepository;

@Component
@RequiredArgsConstructor
public class ImageRowSaver {

	private final StoreRepository storeRepository;
	private final MenuRepository menuRepository;
	private final StoreImageFileRepository storeImageFileRepository;
	private final MenuImageFileRepository menuImageFileRepository;
	private final ImageProcessingCallbackService imageProcessingCallbackService;

	// REQUIRES_NEW 로 분리 — bulk 흐름에서 한 건이 실패해도 나머지 커밋은 유지.
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Long saveStoreImage(Long storeId, String fileName, String awsKey,
			MimeType mimeType, int displayOrder, String sourceUrl) {
		Store store = storeRepository.getReferenceById(storeId);
		StoreImageFile imageFile = StoreImageFile.builder()
			.store(store)
			.name(fileName)
			.mimeType(mimeType)
			.awsKey(awsKey)
			.displayOrder(displayOrder)
			.sourceUrl(sourceUrl)
			.build();
		StoreImageFile savedImage = storeImageFileRepository.save(imageFile);
		imageProcessingCallbackService.applyPendingCallback(savedImage);
		return savedImage.getId();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Long saveMenuImage(Long menuId, String fileName, String awsKey,
			MimeType mimeType, int displayOrder, String sourceUrl) {
		Menu menu = menuRepository.getReferenceById(menuId);
		MenuImageFile imageFile = MenuImageFile.builder()
			.menu(menu)
			.name(fileName)
			.mimeType(mimeType)
			.awsKey(awsKey)
			.displayOrder(displayOrder)
			.sourceUrl(sourceUrl)
			.build();
		MenuImageFile savedImage = menuImageFileRepository.save(imageFile);
		imageProcessingCallbackService.applyPendingCallback(savedImage);
		return savedImage.getId();
	}
}

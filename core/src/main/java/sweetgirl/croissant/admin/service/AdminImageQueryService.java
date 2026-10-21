package sweetgirl.croissant.admin.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.external.aws.dto.AdminImageResponse;
import sweetgirl.croissant.menu.code.MenuErrorCode;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminImageQueryService {

	private final StoreRepository storeRepository;
	private final MenuRepository menuRepository;
	private final StoreImageFileRepository storeImageFileRepository;
	private final MenuImageFileRepository menuImageFileRepository;

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
}

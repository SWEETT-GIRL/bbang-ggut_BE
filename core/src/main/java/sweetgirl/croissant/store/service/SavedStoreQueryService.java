package sweetgirl.croissant.store.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.store.dto.projection.StoreImageThumbnailProjection;
import sweetgirl.croissant.store.dto.response.SavedStoreResponse;
import sweetgirl.croissant.store.entity.SavedStore;
import sweetgirl.croissant.store.repository.SavedStoreRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SavedStoreQueryService {

	private final SavedStoreRepository savedStoreRepository;
	private final StoreImageFileRepository storeImageFileRepository;

	public List<SavedStoreResponse> getSavedStores(Long memberId) {
		List<SavedStore> savedStores = savedStoreRepository.findAllByMemberIdOrderByIdDesc(memberId);

		List<Long> storeIds = savedStores.stream()
			.map(savedStore -> savedStore.getStore().getId())
			.toList();
		Map<Long, String> storeThumbnailMap = getStoreThumbnails(storeIds);

		return savedStores.stream()
			.map(savedStore -> SavedStoreResponse.from(
				savedStore, storeThumbnailMap.get(savedStore.getStore().getId())))
			.toList();
	}

	private Map<Long, String> getStoreThumbnails(List<Long> storeIds) {
		if (storeIds.isEmpty()) {
			return Map.of();
		}
		return storeImageFileRepository.findThumbnailsByStoreIdIn(storeIds).stream()
			.filter(thumbnail -> thumbnail.getResizedKey320() != null || thumbnail.getAwsKey() != null)
			.collect(Collectors.toMap(
				StoreImageThumbnailProjection::getStoreId,
				thumbnail -> thumbnail.getResizedKey320() != null
					? thumbnail.getResizedKey320()
					: thumbnail.getAwsKey(),
				(first, second) -> first));
	}
}

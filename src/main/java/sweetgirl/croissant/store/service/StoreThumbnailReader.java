package sweetgirl.croissant.store.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.store.dto.projection.StoreImageThumbnailProjection;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;

/** 가게 대표 썸네일 — 320 리사이즈가 있으면 그것, 없으면 원본 키. 목록 하나에 쿼리 한 번. (StoreQueryService 와 같은 규칙) */
@Component
@RequiredArgsConstructor
public class StoreThumbnailReader {

	private final StoreImageFileRepository storeImageFileRepository;

	public Map<Long, String> thumbnailsOf(Collection<Long> storeIds) {
		List<Long> distinct = storeIds.stream().distinct().toList();
		if (distinct.isEmpty()) {
			return Map.of();
		}
		return storeImageFileRepository.findThumbnailsByStoreIdIn(distinct).stream()
			.filter(projection -> projection.getResizedKey320() != null || projection.getAwsKey() != null)
			.collect(Collectors.toMap(
				StoreImageThumbnailProjection::getStoreId,
				projection -> projection.getResizedKey320() != null ? projection.getResizedKey320()
					: projection.getAwsKey(),
				(first, second) -> first));
	}
}

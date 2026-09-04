package sweetgirl.croissant.course.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.course.entity.CourseStore;
import sweetgirl.croissant.store.dto.projection.StoreImageThumbnailProjection;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;

/**
 * 코스 대표사진이 없을 때 앱이 그릴 콜라주 재료 — 코스 가게들의 썸네일을 방문 순서대로 최대 4장.
 * 사진 없는 가게는 건너뛴다(개수는 "사진 있는 가게 수"다). 앱은 그 개수로 1장/2장/2×2를 정한다.
 */
@Component
@RequiredArgsConstructor
public class CourseStoreThumbnailReader {

	public static final int MAX_THUMBNAILS = 4;

	private final StoreImageFileRepository storeImageFileRepository;

	/**
	 * 여러 코스를 한 번의 쿼리로 — 목록 화면에서 코스 수만큼 쿼리가 늘면 안 된다.
	 *
	 * @param storeIdsByCourseId 코스별 가게 id, 방문 순서대로
	 */
	public Map<Long, List<String>> readByCourse(Map<Long, List<Long>> storeIdsByCourseId) {
		List<Long> storeIds = storeIdsByCourseId.values().stream()
			.flatMap(List::stream)
			.distinct()
			.toList();
		Map<Long, String> thumbnailByStoreId = thumbnailsOf(storeIds);
		return storeIdsByCourseId.entrySet().stream()
			.collect(Collectors.toMap(
				Map.Entry::getKey,
				entry -> pick(entry.getValue(), thumbnailByStoreId)));
	}

	public List<String> read(List<CourseStore> courseStores) {
		List<Long> storeIds = courseStores.stream()
			.map(courseStore -> courseStore.getStore().getId())
			.toList();
		return pick(storeIds, thumbnailsOf(storeIds.stream().distinct().toList()));
	}

	private Map<Long, String> thumbnailsOf(List<Long> storeIds) {
		if (storeIds.isEmpty()) {
			return Map.of();
		}
		return storeImageFileRepository.findThumbnailsByStoreIdIn(storeIds).stream()
			.filter(p -> p.getResizedKey320() != null || p.getAwsKey() != null)
			.collect(Collectors.toMap(
				StoreImageThumbnailProjection::getStoreId,
				p -> p.getResizedKey320() != null ? p.getResizedKey320() : p.getAwsKey(),
				(first, second) -> first));
	}

	private static List<String> pick(List<Long> storeIdsInVisitOrder, Map<Long, String> thumbnailByStoreId) {
		return storeIdsInVisitOrder.stream()
			.map(thumbnailByStoreId::get)
			.filter(Objects::nonNull)
			.distinct()
			.limit(MAX_THUMBNAILS)
			.toList();
	}
}

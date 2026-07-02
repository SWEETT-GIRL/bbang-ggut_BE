package sweetgirl.croissant.admin.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.dto.response.AdminNaverEnrichCandidateResponse;
import sweetgirl.croissant.admin.dto.response.AdminNaverEnrichQueueItem;
import sweetgirl.croissant.admin.dto.response.AdminNaverEnrichStatsResponse;
import sweetgirl.croissant.admin.dto.response.AdminNaverThrottledItem;
import sweetgirl.croissant.admin.dto.response.AdminStoreResponse;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.dto.projection.StoreIdCountProjection;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreBusinessHour;
import sweetgirl.croissant.store.entity.StoreImageFile;
import sweetgirl.croissant.store.enums.StoreStatus;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.NaverEnrichCandidateRepository;
import sweetgirl.croissant.store.repository.StoreBusinessHourRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreRepository;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminStoreQueryService {

	// 실패 후 재시도 가능 시점 — 매칭 실패한 가게는 7일 후부터 다시 큐에 노출.
	private static final long NAVER_RETRY_AFTER_DAYS = 7;
	// '진짜 무결과(후보 0건)' 가 이 횟수 이상 누적되면 큐에서 파킹(제외). 폐업/오등록/빵집 아님 등
	// 영영 못 찾는 가게의 무한 재시도를 막는다. 어드민 retry / placeId 초기화로 0 리셋되어 재진입.
	private static final int NAVER_MAX_ENRICH_ATTEMPTS = 3;

	private final StoreRepository storeRepository;
	private final StoreBusinessHourRepository storeBusinessHourRepository;
	private final StoreImageFileRepository storeImageFileRepository;
	private final SubscribedStoreRepository subscribedStoreRepository;
	private final MenuRepository menuRepository;
	private final NaverEnrichOrchestrator naverEnrichOrchestrator;
	private final NaverEnrichCandidateRepository candidateRepository;

	public Page<AdminStoreResponse> getStores(String keyword, String statusStr, Pageable pageable) {
		StoreStatus status = statusStr != null ? toStoreStatus(statusStr) : null;
		Page<Store> stores;

		if (keyword != null && status != null) {
			stores = storeRepository.findByNameContainingIgnoreCaseAndStatus(keyword, status, pageable);
		} else if (keyword != null) {
			stores = storeRepository.findByNameContainingIgnoreCase(keyword, pageable);
		} else if (status != null) {
			stores = storeRepository.findByStatus(status, pageable);
		} else {
			stores = storeRepository.findAll(pageable);
		}

		List<Long> storeIds = stores.getContent().stream().map(Store::getId).toList();
		Map<Long, Long> alarmCountMap = getAlarmCountMap(storeIds);
		Map<Long, List<StoreImageFile>> photoMap = getPhotoMap(storeIds);
		Map<Long, List<StoreBusinessHour>> hoursMap = getHoursMap(storeIds);

		return stores.map(store -> AdminStoreResponse.of(
			store,
			hoursMap.getOrDefault(store.getId(), Collections.emptyList()),
			photoMap.getOrDefault(store.getId(), Collections.emptyList()),
			alarmCountMap.getOrDefault(store.getId(), 0L)));
	}

	public AdminStoreResponse getStoreDetail(Long storeId) {
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		return buildResponse(store);
	}

	// ── Naver enrich 큐 ─────────────────────────────────────────────────────

	public Page<AdminNaverEnrichQueueItem> getNaverEnrichQueue(Pageable pageable) {
		return getNaverEnrichQueue(pageable, null);
	}

	public Page<AdminNaverEnrichQueueItem> getNaverEnrichQueue(
			Pageable pageable, NaverEnrichPartition partition) {
		Instant retryThreshold = Instant.now().minus(NAVER_RETRY_AFTER_DAYS, ChronoUnit.DAYS);
		Page<Store> stores = storeRepository.findNaverEnrichQueue(
			retryThreshold,
			NAVER_MAX_ENRICH_ATTEMPTS,
			NaverEnrichPartition.valueOfNullable(partition),
			pageable);
		List<Long> storeIds = stores.getContent().stream().map(Store::getId).toList();
		Map<Long, Long> menuCountMap = getMenuCountMap(storeIds);
		Map<Long, Long> photoCountMap = getPhotoCountMap(storeIds);
		return stores.map(s -> AdminNaverEnrichQueueItem.from(
			s,
			menuCountMap.getOrDefault(s.getId(), 0L),
			photoCountMap.getOrDefault(s.getId(), 0L)));
	}

	// 매칭은 됐지만 사진이 0장인 가게 — 사진 보충 일괄 크롤 큐.
	// Python 스크래퍼는 store.naverPlaceId 가 들어있으면 검색을 스킵하고 바로 사진만 가져옴.
	public Page<AdminNaverEnrichQueueItem> getPhotoBackfillQueue(Pageable pageable) {
		return getPhotoBackfillQueue(pageable, null);
	}

	public Page<AdminNaverEnrichQueueItem> getPhotoBackfillQueue(
			Pageable pageable, NaverEnrichPartition partition) {
		Set<Long> excludedIds = naverEnrichOrchestrator.getPhotoBackfillAttemptedStoreIds();
		Page<Store> stores = storeRepository.findMatchedWithoutPhotos(
			queryExcludedIds(excludedIds),
			excludedIds.isEmpty(),
			NaverEnrichPartition.valueOfNullable(partition),
			pageable);
		List<Long> storeIds = stores.getContent().stream().map(Store::getId).toList();
		Map<Long, Long> menuCountMap = getMenuCountMap(storeIds);
		return stores.map(s -> AdminNaverEnrichQueueItem.from(
			s,
			menuCountMap.getOrDefault(s.getId(), 0L),
			0L));
	}

	public long countPhotoBackfillQueue() {
		return countPhotoBackfillQueue(null);
	}

	public long countPhotoBackfillQueue(NaverEnrichPartition partition) {
		Set<Long> excludedIds = naverEnrichOrchestrator.getPhotoBackfillAttemptedStoreIds();
		return storeRepository.countMatchedWithoutPhotos(
			queryExcludedIds(excludedIds),
			excludedIds.isEmpty(),
			NaverEnrichPartition.valueOfNullable(partition));
	}

	// placeId 는 있으나 메뉴 또는 사진이 비어 enrich 가 안 끝난 가게 — 메뉴+사진 풀 enrich 백필 큐.
	// 수동/후보 매칭(updateNaverPlaceId)으로 placeId 만 채워진 가게가 자동으로 여기 들어와 크롤된다.
	public Page<AdminNaverEnrichQueueItem> getEnrichBackfillQueue(Pageable pageable) {
		return getEnrichBackfillQueue(pageable, null);
	}

	public Page<AdminNaverEnrichQueueItem> getEnrichBackfillQueue(
			Pageable pageable, NaverEnrichPartition partition) {
		Set<Long> excludedIds = naverEnrichOrchestrator.getEnrichBackfillAttemptedStoreIds();
		Page<Store> stores = storeRepository.findMatchedNeedingEnrich(
			queryExcludedIds(excludedIds),
			excludedIds.isEmpty(),
			NaverEnrichPartition.valueOfNullable(partition),
			pageable);
		List<Long> storeIds = stores.getContent().stream().map(Store::getId).toList();
		Map<Long, Long> menuCountMap = getMenuCountMap(storeIds);
		Map<Long, Long> photoCountMap = getPhotoCountMap(storeIds);
		return stores.map(s -> AdminNaverEnrichQueueItem.from(
			s,
			menuCountMap.getOrDefault(s.getId(), 0L),
			photoCountMap.getOrDefault(s.getId(), 0L)));
	}

	public long countEnrichBackfillQueue() {
		return countEnrichBackfillQueue(null);
	}

	public long countEnrichBackfillQueue(NaverEnrichPartition partition) {
		Set<Long> excludedIds = naverEnrichOrchestrator.getEnrichBackfillAttemptedStoreIds();
		return storeRepository.countMatchedNeedingEnrich(
			queryExcludedIds(excludedIds),
			excludedIds.isEmpty(),
			NaverEnrichPartition.valueOfNullable(partition));
	}

	private Set<Long> queryExcludedIds(Set<Long> excludedIds) {
		return excludedIds.isEmpty() ? Set.of(-1L) : excludedIds;
	}

	public AdminNaverEnrichQueueItem getNaverEnrichItem(Long storeId) {
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		long menuCount = getMenuCountMap(List.of(storeId)).getOrDefault(storeId, 0L);
		long photoCount = getPhotoCountMap(List.of(storeId)).getOrDefault(storeId, 0L);
		return AdminNaverEnrichQueueItem.from(store, menuCount, photoCount);
	}

	public Page<AdminNaverThrottledItem> getNaverThrottledList(Pageable pageable) {
		Instant throttleAliveSince = Instant.now().minus(NAVER_RETRY_AFTER_DAYS, ChronoUnit.DAYS);
		Page<Store> stores = storeRepository.findNaverThrottled(throttleAliveSince, pageable);
		List<Long> storeIds = stores.getContent().stream().map(Store::getId).toList();
		Map<Long, Long> menuCountMap = getMenuCountMap(storeIds);
		return stores.map(s -> AdminNaverThrottledItem.from(
			s, menuCountMap.getOrDefault(s.getId(), 0L), NAVER_RETRY_AFTER_DAYS));
	}

	private Map<Long, Long> getMenuCountMap(List<Long> storeIds) {
		if (storeIds.isEmpty()) {
			return Collections.emptyMap();
		}
		return menuRepository.countActiveMenusByStoreIds(storeIds).stream()
			.collect(Collectors.toMap(
				row -> (Long)row[0],
				row -> (Long)row[1]));
	}

	private Map<Long, Long> getPhotoCountMap(List<Long> storeIds) {
		if (storeIds.isEmpty()) {
			return Collections.emptyMap();
		}
		return storeImageFileRepository.countByStoreIdIn(storeIds).stream()
			.collect(Collectors.toMap(
				row -> (Long)row[0],
				row -> (Long)row[1]));
	}

	public List<AdminNaverEnrichCandidateResponse> getCandidates(Long storeId) {
		return candidateRepository.findByStoreIdOrderByDisplayOrderAsc(storeId).stream()
			.map(AdminNaverEnrichCandidateResponse::from)
			.toList();
	}

	public AdminNaverEnrichStatsResponse getNaverEnrichStats() {
		java.time.Instant startedAt = naverEnrichOrchestrator.getStartedAt();
		return new AdminNaverEnrichStatsResponse(
			naverEnrichOrchestrator.isRunning(),
			storeRepository.countNaverMatched(),
			storeRepository.countNaverPending(),
			storeRepository.countNaverFailed(),
			storeRepository.countActiveTotal(),
			naverEnrichOrchestrator.getRunningMode(),
			naverEnrichOrchestrator.getRunningPartition(),
			startedAt != null ? startedAt.toString() : null,
			naverEnrichOrchestrator.getStartedQueueCount());
	}

	private AdminStoreResponse buildResponse(Store store) {
		List<StoreBusinessHour> hours = storeBusinessHourRepository.findByStoreIdOrderByDayOfWeekAsc(store.getId());
		List<StoreImageFile> photos = storeImageFileRepository.findByStoreIdOrderByDisplayOrderAscIdAsc(store.getId());
		long alarmCount = subscribedStoreRepository.countByStoreId(store.getId());
		return AdminStoreResponse.of(store, hours, photos, alarmCount);
	}

	private Map<Long, Long> getAlarmCountMap(List<Long> storeIds) {
		if (storeIds.isEmpty()) {
			return Collections.emptyMap();
		}
		return subscribedStoreRepository.countByStoreIdIn(storeIds).stream()
			.collect(Collectors.toMap(StoreIdCountProjection::getStoreId, StoreIdCountProjection::getCount));
	}

	private Map<Long, List<StoreImageFile>> getPhotoMap(List<Long> storeIds) {
		if (storeIds.isEmpty()) {
			return Collections.emptyMap();
		}
		return storeImageFileRepository.findByStoreIdIn(storeIds).stream()
			.collect(Collectors.groupingBy(img -> img.getStore().getId()));
	}

	private Map<Long, List<StoreBusinessHour>> getHoursMap(List<Long> storeIds) {
		return storeIds.stream()
			.collect(Collectors.toMap(
				id -> id,
				id -> storeBusinessHourRepository.findByStoreIdOrderByDayOfWeekAsc(id)));
	}

	private StoreStatus toStoreStatus(String statusStr) {
		return "active".equals(statusStr) ? StoreStatus.ACTIVE : StoreStatus.DELETED;
	}
}

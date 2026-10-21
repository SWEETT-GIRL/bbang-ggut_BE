package sweetgirl.croissant.admin.service;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.dto.request.AdminNaverEnrichCandidateRequest;
import sweetgirl.croissant.admin.dto.request.AdminStoreCreateRequest;
import sweetgirl.croissant.admin.dto.request.AdminStoreUpdateRequest;
import sweetgirl.croissant.admin.dto.request.BusinessHourRequest;
import sweetgirl.croissant.admin.dto.response.AdminStoreResponse;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.AddressParser;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.entity.NaverEnrichCandidate;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreBusinessHour;
import sweetgirl.croissant.store.entity.StoreImageFile;
import sweetgirl.croissant.store.enums.StoreSource;
import sweetgirl.croissant.store.enums.StoreStatus;
import sweetgirl.croissant.store.event.StoreSearchNameChangedEvent;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.NaverEnrichCandidateRepository;
import sweetgirl.croissant.store.repository.StoreBusinessHourRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreRepository;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminStoreCommandService {

	private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

	private static final Map<String, DayOfWeek> KEY_TO_DAY = Map.of(
		"mon", DayOfWeek.MONDAY,
		"tue", DayOfWeek.TUESDAY,
		"wed", DayOfWeek.WEDNESDAY,
		"thu", DayOfWeek.THURSDAY,
		"fri", DayOfWeek.FRIDAY,
		"sat", DayOfWeek.SATURDAY,
		"sun", DayOfWeek.SUNDAY);

	private final StoreRepository storeRepository;
	private final StoreBusinessHourRepository storeBusinessHourRepository;
	private final StoreImageFileRepository storeImageFileRepository;
	private final SubscribedStoreRepository subscribedStoreRepository;
	private final MenuRepository menuRepository;
	private final NaverEnrichOrchestrator naverEnrichOrchestrator;
	private final NaverEnrichCandidateRepository candidateRepository;
	private final ApplicationEventPublisher eventPublisher;

	public AdminStoreResponse createStore(AdminStoreCreateRequest request) {
		Point location = toPoint(request.coord().latitude(), request.coord().longitude());
		String[] addr = AddressParser.parse(request.address());

		Store store = Store.builder()
			.name(request.name())
			.address(request.address())
			.region1st(addr[0])
			.region2nd(addr[1])
			.region3rd(addr[2])
			.location(location)
			.phone(request.phone())
			.naverPlaceId(request.naverPlaceId())
			.kakaoPlaceId(request.kakaoPlaceId())
			.source(StoreSource.ADMIN_REGISTERED)
			.category(request.category())
			.build();
		store = storeRepository.save(store);
		eventPublisher.publishEvent(StoreSearchNameChangedEvent.of(store.getName()));

		saveBusinessHours(store, request.hours());
		return buildResponse(store);
	}

	public void updateStore(Long storeId, AdminStoreUpdateRequest request) {
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));

		Point newLocation = request.coord() != null
			? toPoint(request.coord().latitude(), request.coord().longitude()) : null;
		String[] addr = request.address() != null ? AddressParser.parse(request.address()) : new String[3];
		StoreStatus newStatus = resolveStatus(request);
		String previousName = store.getName();
		StoreStatus previousStatus = store.getStatus();

		store.update(request.name(), request.address(), newLocation, addr[0], addr[1], addr[2],
			request.phone(), newStatus, request.category());
		// 이름이나 노출 여부가 바뀌면 자동완성 결과가 달라진다 — 옛 이름으로 캐시된 검색어도 지워야 해서 둘 다 넘긴다
		if (!Objects.equals(previousName, store.getName()) || previousStatus != store.getStatus()) {
			eventPublisher.publishEvent(StoreSearchNameChangedEvent.of(previousName, store.getName()));
		}

		if (request.hours() != null) {
			storeBusinessHourRepository.deleteByStoreId(storeId);
			saveBusinessHours(store, request.hours());
		}
	}

	public void deleteStore(Long storeId) {
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		boolean wasActive = store.getStatus() != StoreStatus.DELETED;
		store.update(null, null, null, null, null, null, null, StoreStatus.DELETED, null);
		candidateRepository.deleteByStoreId(storeId);
		if (wasActive) {
			eventPublisher.publishEvent(StoreSearchNameChangedEvent.of(store.getName()));
		}
	}

	public void deleteNaverClosedSuspect(Long storeId) {
		deleteStore(storeId);
	}

	public void updateNaverPlaceId(Long storeId, String naverPlaceId, boolean replaceMenus) {
		updateNaverPlaceId(storeId, naverPlaceId, replaceMenus, null, null, null);
	}

	public void updateNaverPlaceId(
			Long storeId,
			String naverPlaceId,
			boolean replaceMenus,
			String address,
			Double latitude,
			Double longitude) {
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		// DB 의 unique 제약이 막아주지만, 사전 검사로 명시적 409 를 돌려줘서 운영자가 원인을 바로 본다.
		storeRepository.findByNaverPlaceId(naverPlaceId).ifPresent(owner -> {
			if (!owner.getId().equals(storeId)) {
				throw new StoreException(StoreErrorCode.NAVER_PLACE_ID_DUPLICATE);
			}
		});
		if (replaceMenus) {
			menuRepository.softDeleteByStoreId(storeId);
		}
		Point newLocation = latitude != null && longitude != null ? toPoint(latitude, longitude) : null;
		String[] addr = address != null ? AddressParser.parse(address) : new String[3];
		store.update(null, address, newLocation, addr[0], addr[1], addr[2], null, null, null);
		store.markNaverMatched(naverPlaceId);
		// 매칭 확정됐으니 보관 중이던 후보 목록 정리
		candidateRepository.deleteByStoreId(storeId);
	}

	public void replaceCandidates(Long storeId, List<AdminNaverEnrichCandidateRequest> requests) {
		if (!storeRepository.existsById(storeId)) {
			throw new StoreException(StoreErrorCode.STORE_NOT_FOUND);
		}
		candidateRepository.deleteByStoreId(storeId);
		int order = 0;
		for (AdminNaverEnrichCandidateRequest candidateRequest : requests) {
			candidateRepository.save(NaverEnrichCandidate.builder()
				.storeId(storeId)
				.naverPlaceId(candidateRequest.naverPlaceId())
				.name(candidateRequest.name())
				.address(candidateRequest.address())
				.lat(candidateRequest.lat())
				.lng(candidateRequest.lng())
				.displayOrder(order++)
				.build());
		}
	}

	public void markNaverEnrichFailed(Long storeId, boolean noCandidates) {
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		store.markNaverEnrichFailed(noCandidates);
	}

	public void markNaverBackfillAttempted(Long storeId, String mode) {
		if (!storeRepository.existsById(storeId)) {
			throw new StoreException(StoreErrorCode.STORE_NOT_FOUND);
		}
		naverEnrichOrchestrator.markBackfillAttempted(mode, storeId);
	}

	public void clearNaverPlaceId(Long storeId) {
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		store.clearNaverMatch();
	}

	public void retryNaverEnrich(Long storeId) {
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		store.resetNaverEnrichThrottle();
	}

	private AdminStoreResponse buildResponse(Store store) {
		List<StoreBusinessHour> hours = storeBusinessHourRepository.findByStoreIdOrderByDayOfWeekAsc(store.getId());
		List<StoreImageFile> photos = storeImageFileRepository.findByStoreIdOrderByDisplayOrderAscIdAsc(store.getId());
		long alarmCount = subscribedStoreRepository.countByStoreId(store.getId());
		return AdminStoreResponse.of(store, hours, photos, alarmCount);
	}

	private void saveBusinessHours(Store store, Map<String, BusinessHourRequest> hours) {
		if (hours == null || hours.isEmpty()) {
			return;
		}
		List<StoreBusinessHour> entities = hours.entrySet().stream()
			.filter(e -> KEY_TO_DAY.containsKey(e.getKey()))
			.map(e -> {
				BusinessHourRequest bh = e.getValue();
				DayOfWeek day = KEY_TO_DAY.get(e.getKey());
				return StoreBusinessHour.builder()
					.store(store)
					.dayOfWeek(day)
					.openTime(bh.closed() ? null : LocalTime.of(bh.openHour(), bh.openMin()))
					.closeTime(bh.closed() ? null : LocalTime.of(bh.closeHour(), bh.closeMin()))
					.isClosed(bh.closed())
					.build();
			})
			.toList();
		storeBusinessHourRepository.saveAll(entities);
	}

	private StoreStatus resolveStatus(AdminStoreUpdateRequest request) {
		if ("active".equals(request.status())) {
			return StoreStatus.ACTIVE;
		}
		if ("inactive".equals(request.status())) {
			return StoreStatus.DELETED;
		}
		if (request.archivedAt() != null && !request.archivedAt().isBlank()) {
			return StoreStatus.DELETED;
		}
		return null;
	}

	private Point toPoint(double latitude, double longitude) {
		return GEOMETRY_FACTORY.createPoint(new Coordinate(longitude, latitude));
	}
}

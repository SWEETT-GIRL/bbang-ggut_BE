package sweetgirl.croissant.admin.service;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.dto.request.AdminStoreCreateRequest;
import sweetgirl.croissant.admin.dto.request.AdminStoreUpdateRequest;
import sweetgirl.croissant.admin.dto.request.BusinessHourRequest;
import sweetgirl.croissant.admin.dto.response.AdminStoreResponse;
import sweetgirl.croissant.store.AddressParser;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreBusinessHour;
import sweetgirl.croissant.store.entity.StoreImageFile;
import sweetgirl.croissant.store.enums.StoreSource;
import sweetgirl.croissant.store.enums.StoreStatus;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreBusinessHourRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreRepository;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminStoreService {

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

	@Transactional
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
			.kakaoPlaceId(request.kakaoPlaceId())
			.source(StoreSource.ADMIN_REGISTERED)
			.category(request.category())
			.build();
		store = storeRepository.save(store);

		saveBusinessHours(store, request.hours());
		return buildResponse(store);
	}

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

	@Transactional
	public void updateStore(Long storeId, AdminStoreUpdateRequest request) {
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));

		Point newLocation = request.coord() != null
			? toPoint(request.coord().latitude(), request.coord().longitude()) : null;
		String[] addr = request.address() != null ? AddressParser.parse(request.address()) : new String[3];
		StoreStatus newStatus = resolveStatus(request);

		store.update(request.name(), request.address(), newLocation, addr[0], addr[1], addr[2],
			request.phone(), newStatus, request.category());

		if (request.hours() != null) {
			storeBusinessHourRepository.deleteByStoreId(storeId);
			saveBusinessHours(store, request.hours());
		}
	}

	@Transactional
	public void deleteStore(Long storeId) {
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		store.update(null, null, null, null, null, null, null, StoreStatus.DELETED, null);
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

	private Map<Long, Long> getAlarmCountMap(List<Long> storeIds) {
		if (storeIds.isEmpty()) {
			return Collections.emptyMap();
		}
		return subscribedStoreRepository.countByStoreIdIn(storeIds).stream()
			.collect(Collectors.toMap(
				row -> (Long)row[0],
				row -> (Long)row[1]));
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

	private Point toPoint(double latitude, double longitude) {
		return GEOMETRY_FACTORY.createPoint(new Coordinate(longitude, latitude));
	}

	private StoreStatus toStoreStatus(String statusStr) {
		return "active".equals(statusStr) ? StoreStatus.ACTIVE : StoreStatus.DELETED;
	}
}

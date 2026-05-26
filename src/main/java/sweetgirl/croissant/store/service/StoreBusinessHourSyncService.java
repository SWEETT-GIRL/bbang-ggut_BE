package sweetgirl.croissant.store.service;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.external.google.client.GooglePlacesClient;
import sweetgirl.croissant.external.google.dto.GooglePlaceDetailsResponse;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.dto.response.BusinessHourSyncResponse;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreBusinessHour;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreBusinessHourRepository;
import sweetgirl.croissant.store.repository.StoreRepository;

@Service
@RequiredArgsConstructor
public class StoreBusinessHourSyncService {

	private static final Logger log = LoggerFactory.getLogger(StoreBusinessHourSyncService.class);
	private static final long API_DELAY_MS = 200;

	private final StoreRepository storeRepository;
	private final StoreBusinessHourRepository storeBusinessHourRepository;
	private final GooglePlacesClient googlePlacesClient;

	/**
	 * DB의 모든 가게 영업시간을 Google Places API로 동기화합니다.
	 * 가게당 최소 200ms 딜레이를 적용해 API 요청 한도를 준수합니다.
	 */
	public BusinessHourSyncResponse syncAll() {
		List<Store> stores = storeRepository.findAll();
		int success = 0;
		int skipped = 0;
		int failed = 0;

		for (Store store : stores) {
			try {
				boolean synced = syncStore(store);
				if (synced) {
					success++;
				} else {
					skipped++;
				}
				Thread.sleep(API_DELAY_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				log.warn("영업시간 일괄 동기화 중단 - storeId={}", store.getId());
				break;
			} catch (Exception e) {
				log.warn("영업시간 동기화 실패 - storeId={}, name={}, error={}", store.getId(), store.getName(),
					e.getMessage());
				failed++;
			}
		}

		log.info("영업시간 일괄 동기화 완료 - total={}, success={}, skipped={}, failed={}",
			stores.size(), success, skipped, failed);
		return new BusinessHourSyncResponse(stores.size(), success, skipped, failed);
	}

	/**
	 * 특정 가게의 영업시간을 Google Places API로 동기화합니다.
	 */
	@Transactional
	public boolean syncByStoreId(Long storeId) {
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		return syncStore(store);
	}

	private boolean syncStore(Store store) {
		Optional<String> placeIdOpt = googlePlacesClient.findPlaceId(store.getName(), store.getAddress());
		if (placeIdOpt.isEmpty()) {
			log.debug("Google Places 검색 결과 없음 - storeId={}, name={}", store.getId(), store.getName());
			return false;
		}

		Optional<GooglePlaceDetailsResponse.OpeningHours> hoursOpt = googlePlacesClient
			.getOpeningHours(placeIdOpt.get());

		List<StoreBusinessHour> businessHours = buildBusinessHours(store, hoursOpt.orElse(null));

		storeBusinessHourRepository.deleteByStoreId(store.getId());
		storeBusinessHourRepository.saveAll(businessHours);
		return true;
	}

	private List<StoreBusinessHour> buildBusinessHours(Store store,
			GooglePlaceDetailsResponse.OpeningHours openingHours) {
		Map<DayOfWeek, LocalTime[]> hoursMap = new EnumMap<>(DayOfWeek.class);

		if (openingHours != null && openingHours.periods() != null) {
			for (GooglePlaceDetailsResponse.Period period : openingHours.periods()) {
				if (period.open() == null) {
					continue;
				}
				DayOfWeek day = toJavaDayOfWeek(period.open().day());
				LocalTime openTime = LocalTime.of(period.open().hour(), period.open().minute());
				LocalTime closeTime = period.close() != null
					? LocalTime.of(period.close().hour(), period.close().minute()) : null;
				hoursMap.put(day, new LocalTime[] {openTime, closeTime});
			}
		}

		// 7일 모두 행 생성 — 맵에 없는 요일은 휴무(isClosed=true)
		return Arrays.stream(DayOfWeek.values())
			.map(day -> {
				LocalTime[] times = hoursMap.get(day);
				if (times != null) {
					return StoreBusinessHour.builder()
						.store(store)
						.dayOfWeek(day)
						.openTime(times[0])
						.closeTime(times[1])
						.isClosed(false)
						.build();
				}
				return StoreBusinessHour.builder()
					.store(store)
					.dayOfWeek(day)
					.isClosed(true)
					.build();
			})
			.toList();
	}

	/** Google 요일(0=일~6=토) → Java DayOfWeek 변환 */
	private DayOfWeek toJavaDayOfWeek(int googleDay) {
		return switch (googleDay) {
			case 0 -> DayOfWeek.SUNDAY;
			case 1 -> DayOfWeek.MONDAY;
			case 2 -> DayOfWeek.TUESDAY;
			case 3 -> DayOfWeek.WEDNESDAY;
			case 4 -> DayOfWeek.THURSDAY;
			case 5 -> DayOfWeek.FRIDAY;
			case 6 -> DayOfWeek.SATURDAY;
			default -> throw new IllegalArgumentException("유효하지 않은 Google 요일 값: " + googleDay);
		};
	}
}

package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Point;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import sweetgirl.croissant.admin.dto.request.AdminStoreCreateRequest;
import sweetgirl.croissant.admin.dto.request.AdminStoreUpdateRequest;
import sweetgirl.croissant.admin.dto.request.BusinessHourRequest;
import sweetgirl.croissant.admin.dto.response.AdminStoreResponse;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.enums.StoreStatus;
import sweetgirl.croissant.store.event.StoreSearchNameChangedEvent;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.NaverEnrichCandidateRepository;
import sweetgirl.croissant.store.repository.StoreBusinessHourRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreRepository;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;

@ExtendWith(MockitoExtension.class)
class AdminStoreCommandServiceTest {

	@Mock
	StoreRepository storeRepository;
	@Mock
	StoreBusinessHourRepository storeBusinessHourRepository;
	@Mock
	StoreImageFileRepository storeImageFileRepository;
	@Mock
	SubscribedStoreRepository subscribedStoreRepository;
	@Mock
	MenuRepository menuRepository;
	@Mock
	NaverEnrichOrchestrator naverEnrichOrchestrator;
	@Mock
	NaverEnrichCandidateRepository candidateRepository;
	@Mock
	ApplicationEventPublisher eventPublisher;

	@InjectMocks
	AdminStoreCommandService adminStoreCommandService;

	@Test
	@DisplayName("createStore: 가게를 저장하고 영업시간을 저장한다")
	void createStore_savesStoreAndBusinessHours() {
		Store saved = mock(Store.class);
		given(saved.getId()).willReturn(1L);
		given(saved.getName()).willReturn("소금빵집");
		given(saved.getStatus()).willReturn(StoreStatus.ACTIVE);
		given(storeRepository.save(any(Store.class))).willReturn(saved);
		given(subscribedStoreRepository.countByStoreId(1L)).willReturn(0L);
		given(storeImageFileRepository.findByStoreIdOrderByDisplayOrderAscIdAsc(1L))
			.willReturn(Collections.emptyList());
		given(storeBusinessHourRepository.findByStoreIdOrderByDayOfWeekAsc(1L)).willReturn(Collections.emptyList());

		AdminStoreCreateRequest request = new AdminStoreCreateRequest(
			"소금빵집",
			"서울시 강남구 테헤란로 1",
			new AdminStoreCreateRequest.CoordRequest(37.5, 127.0),
			"02-1234-5678",
			"123456",
			null,
			"active",
			null,
			Map.of("mon", new BusinessHourRequest(9, 0, 21, 0, false)));

		AdminStoreResponse response = adminStoreCommandService.createStore(request);

		verify(storeRepository).save(any(Store.class));
		verify(storeBusinessHourRepository).saveAll(anyList());
		verify(eventPublisher).publishEvent(StoreSearchNameChangedEvent.of("소금빵집"));
		assertThat(response.name()).isEqualTo("소금빵집");
		assertThat(response.status()).isEqualTo("active");
	}

	@Test
	@DisplayName("updateStore: businessHours를 요청값으로 교체한다")
	void updateStore_replacesBusinessHours() {
		Store store = mock(Store.class);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));

		AdminStoreUpdateRequest request = new AdminStoreUpdateRequest(
			"새이름", null, null, null, "active", null, null,
			Map.of("mon", new BusinessHourRequest(9, 0, 21, 0, false)));

		adminStoreCommandService.updateStore(1L, request);

		verify(storeBusinessHourRepository).deleteByStoreId(1L);
		verify(storeBusinessHourRepository).saveAll(anyList());
	}

	@Test
	@DisplayName("deleteStore: 가게를 DELETED 처리하고 네이버 후보를 정리한다")
	void deleteStore_marksDeletedAndClearsCandidates() {
		Store store = mock(Store.class);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));

		adminStoreCommandService.deleteStore(1L);

		verify(store).update(null, null, null, null, null, null, null, StoreStatus.DELETED, null);
		verify(candidateRepository).deleteByStoreId(1L);
	}

	@Test
	@DisplayName("updateStore: 이름이 바뀌면 옛 이름·새 이름 둘 다 자동완성 캐시 무효화 이벤트로 넘긴다")
	void updateStore_publishesOldAndNewNameWhenRenamed() {
		Store store = activeStore("옛이름");
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));

		adminStoreCommandService.updateStore(1L, updateRequest("새이름", "active"));

		verify(eventPublisher).publishEvent(StoreSearchNameChangedEvent.of("옛이름", "새이름"));
	}

	@Test
	@DisplayName("updateStore: 이름은 그대로고 비활성화만 하면 그 이름으로 무효화한다 — 자동완성에서 빠져야 한다")
	void updateStore_publishesNameWhenDeactivated() {
		Store store = activeStore("소금빵집");
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));

		adminStoreCommandService.updateStore(1L, updateRequest(null, "inactive"));

		verify(eventPublisher).publishEvent(StoreSearchNameChangedEvent.of("소금빵집"));
	}

	@Test
	@DisplayName("updateStore: 이름·상태가 그대로면 무효화하지 않는다 — 전화번호 수정 같은 변경으로 캐시를 비우지 않는다")
	void updateStore_skipsEventWhenNameAndStatusUnchanged() {
		Store store = activeStore("소금빵집");
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));

		adminStoreCommandService.updateStore(1L, updateRequest(null, "active"));

		verify(eventPublisher, never()).publishEvent(any(StoreSearchNameChangedEvent.class));
	}

	@Test
	@DisplayName("deleteStore: 노출 중이던 가게를 지우면 그 이름으로 무효화한다")
	void deleteStore_publishesNameWhenActiveStoreDeleted() {
		Store store = activeStore("소금빵집");
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));

		adminStoreCommandService.deleteStore(1L);

		verify(eventPublisher).publishEvent(StoreSearchNameChangedEvent.of("소금빵집"));
	}

	@Test
	@DisplayName("deleteStore: 이미 삭제된 가게면 무효화하지 않는다 — 자동완성 결과가 달라지지 않는다")
	void deleteStore_skipsEventWhenAlreadyDeleted() {
		Store store = activeStore("소금빵집");
		store.update(null, null, null, null, null, null, null, StoreStatus.DELETED, null);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));

		adminStoreCommandService.deleteStore(1L);

		verify(eventPublisher, never()).publishEvent(any(StoreSearchNameChangedEvent.class));
	}

	private static Store activeStore(String name) {
		return Store.builder().name(name).address("서울 마포구 월드컵로 1").build();
	}

	private static AdminStoreUpdateRequest updateRequest(String name, String status) {
		return new AdminStoreUpdateRequest(name, null, null, null, status, null, null, null);
	}

	@Test
	@DisplayName("deleteNaverClosedSuspect: 폐업 의심 가게를 soft-delete한다")
	void deleteNaverClosedSuspect_marksDeleted() {
		Store store = mock(Store.class);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));

		adminStoreCommandService.deleteNaverClosedSuspect(1L);

		verify(store).update(null, null, null, null, null, null, null, StoreStatus.DELETED, null);
		verify(candidateRepository).deleteByStoreId(1L);
	}

	@Test
	@DisplayName("updateNaverPlaceId: 다른 store 가 이미 같은 placeId 를 쓰면 409 로 거절한다")
	void updateNaverPlaceId_duplicate_throws() {
		Store target = mock(Store.class);
		Store other = mock(Store.class);
		given(other.getId()).willReturn(2L);
		given(storeRepository.findById(1L)).willReturn(Optional.of(target));
		given(storeRepository.findByNaverPlaceId("place-abc")).willReturn(Optional.of(other));

		assertThatThrownBy(() -> adminStoreCommandService.updateNaverPlaceId(1L, "place-abc", false))
			.isInstanceOf(StoreException.class)
			.hasFieldOrPropertyWithValue("errorCode", StoreErrorCode.NAVER_PLACE_ID_DUPLICATE);

		verify(target, org.mockito.Mockito.never()).markNaverMatched(any());
	}

	@Test
	@DisplayName("updateNaverPlaceId: 같은 store 가 본인 placeId 를 다시 저장하면 통과한다")
	void updateNaverPlaceId_sameStore_passes() {
		Store target = mock(Store.class);
		given(target.getId()).willReturn(1L);
		given(storeRepository.findById(1L)).willReturn(Optional.of(target));
		given(storeRepository.findByNaverPlaceId("place-abc")).willReturn(Optional.of(target));

		adminStoreCommandService.updateNaverPlaceId(1L, "place-abc", false);

		verify(target).markNaverMatched("place-abc");
		verify(candidateRepository).deleteByStoreId(1L);
	}

	@Test
	@DisplayName("updateNaverPlaceId: placeId 가 신규면 markNaverMatched 호출한다")
	void updateNaverPlaceId_new_passes() {
		Store target = mock(Store.class);
		given(storeRepository.findById(1L)).willReturn(Optional.of(target));
		given(storeRepository.findByNaverPlaceId("place-abc")).willReturn(Optional.empty());

		adminStoreCommandService.updateNaverPlaceId(1L, "place-abc", false);

		verify(target).markNaverMatched("place-abc");
		verify(candidateRepository).deleteByStoreId(1L);
	}

	@Test
	@DisplayName("updateNaverPlaceId: 네이버 후보 주소와 좌표가 있으면 가게 주소도 함께 갱신한다")
	void updateNaverPlaceId_withAddress_updatesStoreLocation() {
		Store target = mock(Store.class);
		String address = "경기도 하남시 신장1로3번길 62";
		given(storeRepository.findById(1L)).willReturn(Optional.of(target));
		given(storeRepository.findByNaverPlaceId("1009971635")).willReturn(Optional.empty());

		adminStoreCommandService.updateNaverPlaceId(1L, "1009971635", false, address, 37.539, 127.214);

		verify(target).update(
			isNull(),
			eq(address),
			any(Point.class),
			// 시·도는 짧게 접고, 도로명은 동 자리에 넣지 않는다 (AddressParser, 2026-09-10)
			eq("경기"),
			eq("하남시"),
			isNull(),
			isNull(),
			isNull(),
			isNull());
		verify(target).markNaverMatched("1009971635");
		verify(candidateRepository).deleteByStoreId(1L);
	}
}

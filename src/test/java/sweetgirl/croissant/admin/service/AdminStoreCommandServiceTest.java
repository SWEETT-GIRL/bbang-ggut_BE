package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Point;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import sweetgirl.croissant.admin.dto.request.AdminStoreCreateRequest;
import sweetgirl.croissant.admin.dto.request.AdminStoreUpdateRequest;
import sweetgirl.croissant.admin.dto.request.BusinessHourRequest;
import sweetgirl.croissant.admin.dto.response.AdminStoreResponse;
import sweetgirl.croissant.external.opensearch.event.StoreAddressChangedEvent;
import sweetgirl.croissant.external.opensearch.event.StoreDeletedEvent;
import sweetgirl.croissant.external.opensearch.event.StoreNameChangedEvent;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.enums.StoreStatus;
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
	@DisplayName("deleteNaverClosedSuspect: 폐업 의심 가게를 soft-delete한다")
	void deleteNaverClosedSuspect_marksDeleted() {
		Store store = mock(Store.class);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));

		adminStoreCommandService.deleteNaverClosedSuspect(1L);

		verify(store).update(null, null, null, null, null, null, null, StoreStatus.DELETED, null);
		verify(candidateRepository).deleteByStoreId(1L);
	}

	@Test
	@DisplayName("updateStore: 주소가 변경되면 StoreAddressChangedEvent가 발행된다")
	void updateStore_addressChanged_publishesAddressEvent() {
		Store store = mock(Store.class);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));

		AdminStoreUpdateRequest request = new AdminStoreUpdateRequest(
			null, "서울시 마포구 홍익로 1", null, null, null, null, null, null);

		adminStoreCommandService.updateStore(1L, request);

		ArgumentCaptor<StoreAddressChangedEvent> captor = ArgumentCaptor.forClass(StoreAddressChangedEvent.class);
		verify(eventPublisher).publishEvent(captor.capture());
		assertThat(captor.getValue().storeId()).isEqualTo(1L);
	}

	@Test
	@DisplayName("updateStore: 이름이 변경되면 StoreNameChangedEvent가 발행된다")
	void updateStore_nameChanged_publishesNameEvent() {
		Store store = mock(Store.class);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));

		AdminStoreUpdateRequest request = new AdminStoreUpdateRequest(
			"새이름", null, null, null, null, null, null, null);

		adminStoreCommandService.updateStore(1L, request);

		ArgumentCaptor<StoreNameChangedEvent> captor = ArgumentCaptor.forClass(StoreNameChangedEvent.class);
		verify(eventPublisher).publishEvent(captor.capture());
		assertThat(captor.getValue().storeId()).isEqualTo(1L);
	}

	@Test
	@DisplayName("updateStore: 이름과 주소가 동시에 변경되면 두 이벤트가 모두 발행된다")
	void updateStore_nameAndAddressChanged_publishesBothEvents() {
		Store store = mock(Store.class);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));

		AdminStoreUpdateRequest request = new AdminStoreUpdateRequest(
			"새이름", "서울시 마포구 홍익로 1", null, null, null, null, null, null);

		adminStoreCommandService.updateStore(1L, request);

		verify(eventPublisher).publishEvent(any(StoreAddressChangedEvent.class));
		verify(eventPublisher).publishEvent(any(StoreNameChangedEvent.class));
	}

	@Test
	@DisplayName("updateStore: 영업시간만 변경되면 이벤트를 발행하지 않는다")
	void updateStore_hoursOnlyChanged_noEventPublished() {
		Store store = mock(Store.class);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));

		AdminStoreUpdateRequest request = new AdminStoreUpdateRequest(
			null, null, null, null, null, null, null,
			Map.of("mon", new BusinessHourRequest(10, 0, 20, 0, false)));

		adminStoreCommandService.updateStore(1L, request);

		verify(eventPublisher, org.mockito.Mockito.never()).publishEvent(any());
	}

	@Test
	@DisplayName("deleteStore: 삭제 시 StoreDeletedEvent가 해당 storeId로 발행된다")
	void deleteStore_publishesStoreDeletedEvent() {
		Store store = mock(Store.class);
		given(storeRepository.findById(7L)).willReturn(Optional.of(store));

		adminStoreCommandService.deleteStore(7L);

		ArgumentCaptor<StoreDeletedEvent> captor = ArgumentCaptor.forClass(StoreDeletedEvent.class);
		verify(eventPublisher).publishEvent(captor.capture());
		assertThat(captor.getValue().storeId()).isEqualTo(7L);
	}

	@Test
	@DisplayName("deleteNaverClosedSuspect: 삭제 시 StoreDeletedEvent가 해당 storeId로 발행된다")
	void deleteNaverClosedSuspect_publishesStoreDeletedEvent() {
		Store store = mock(Store.class);
		given(storeRepository.findById(8L)).willReturn(Optional.of(store));

		adminStoreCommandService.deleteNaverClosedSuspect(8L);

		ArgumentCaptor<StoreDeletedEvent> captor = ArgumentCaptor.forClass(StoreDeletedEvent.class);
		verify(eventPublisher).publishEvent(captor.capture());
		assertThat(captor.getValue().storeId()).isEqualTo(8L);
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
			eq("경기도"),
			eq("하남시"),
			eq("신장1로3번길"),
			isNull(),
			isNull(),
			isNull());
		verify(target).markNaverMatched("1009971635");
		verify(candidateRepository).deleteByStoreId(1L);
	}
}

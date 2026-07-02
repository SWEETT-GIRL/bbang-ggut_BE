package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import sweetgirl.croissant.admin.dto.response.AdminStoreResponse;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.store.dto.projection.StoreIdCountProjection;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.enums.StoreStatus;
import sweetgirl.croissant.store.repository.StoreBusinessHourRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreRepository;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;

@ExtendWith(MockitoExtension.class)
class AdminStoreQueryServiceTest {

	@Mock
	StoreRepository storeRepository;
	@Mock
	StoreBusinessHourRepository storeBusinessHourRepository;
	@Mock
	StoreImageFileRepository storeImageFileRepository;
	@Mock
	SubscribedStoreRepository subscribedStoreRepository;

	@InjectMocks
	AdminStoreQueryService adminStoreQueryService;

	@Test
	@DisplayName("getStores: keyword 없이 전체 목록을 페이지로 반환한다")
	void getStores_noFilters_returnsPage() {
		Store store = mock(Store.class);
		given(store.getId()).willReturn(1L);
		given(store.getStatus()).willReturn(StoreStatus.ACTIVE);

		PageRequest pageable = PageRequest.of(0, 20);
		given(storeRepository.findAll(pageable))
			.willReturn(new PageImpl<>(List.of(store), pageable, 1));
		StoreIdCountProjection alarmCount = mock(StoreIdCountProjection.class);
		given(alarmCount.getStoreId()).willReturn(1L);
		given(alarmCount.getCount()).willReturn(5L);
		given(subscribedStoreRepository.countByStoreIdIn(List.of(1L))).willReturn(List.of(alarmCount));
		given(storeImageFileRepository.findByStoreIdIn(List.of(1L))).willReturn(Collections.emptyList());
		given(storeBusinessHourRepository.findByStoreIdOrderByDayOfWeekAsc(1L)).willReturn(Collections.emptyList());

		Page<AdminStoreResponse> result = adminStoreQueryService.getStores(null, null, pageable);

		assertThat(result.getContent()).hasSize(1);
		assertThat(result.getContent().get(0).alarmCount()).isEqualTo(5L);
	}

	@Test
	@DisplayName("getStoreDetail: 없는 가게는 예외를 던진다")
	void getStoreDetail_notFound_throwsException() {
		given(storeRepository.findById(999L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> adminStoreQueryService.getStoreDetail(999L))
			.isInstanceOf(CustomException.class);
	}
}

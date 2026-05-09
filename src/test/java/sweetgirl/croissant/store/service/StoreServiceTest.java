package sweetgirl.croissant.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;

import sweetgirl.croissant.common.enums.StoreStatus;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.dto.SubscribedStoreResponse;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreImageFile;
import sweetgirl.croissant.store.entity.SubscribedStore;
import sweetgirl.croissant.store.repository.StoreBusinessHourRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreRepository;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;

@ExtendWith(MockitoExtension.class)
class StoreServiceTest {

	@Mock
	StoreRepository storeRepository;

	@Mock
	StoreBusinessHourRepository storeBusinessHourRepository;

	@Mock
	StoreImageFileRepository storeImageFileRepository;

	@Mock
	SubscribedStoreRepository subscribedStoreRepository;

	@InjectMocks
	StoreService storeService;

	@Test
	@DisplayName("getSubscribedStores: 구독 가게 목록을 SliceResponse로 반환한다")
	void getSubscribedStores_returnsDtoList() {
		Member member = mock(Member.class);
		PageRequest pageable = PageRequest.of(0, 10);

		Store store = mock(Store.class);
		given(store.getId()).willReturn(1L);
		given(store.getName()).willReturn("뚜레쥬르");
		given(store.getAddress()).willReturn("서울시 강남구 테헤란로 1");
		given(store.getStatus()).willReturn(StoreStatus.ACTIVE);

		StoreImageFile image = mock(StoreImageFile.class);
		given(image.getAwsKey()).willReturn("stores/1/thumbnail.jpg");

		SubscribedStore subscribedStore = mock(SubscribedStore.class);
		given(subscribedStore.getStore()).willReturn(store);

		given(subscribedStoreRepository.findAllByMemberOrderByCreatedAtDesc(member, pageable))
			.willReturn(new SliceImpl<>(List.of(subscribedStore), pageable, false));
		given(storeImageFileRepository.findByStoreId(1L)).willReturn(List.of(image));

		SliceResponse<SubscribedStoreResponse> result = storeService.getSubscribedStores(member, pageable);

		assertThat(result.content()).hasSize(1);
		assertThat(result.hasNext()).isFalse();
		assertThat(result.content().get(0).storeId()).isEqualTo(1L);
		assertThat(result.content().get(0).storeName()).isEqualTo("뚜레쥬르");
		assertThat(result.content().get(0).address()).isEqualTo("서울시 강남구 테헤란로 1");
		assertThat(result.content().get(0).status()).isEqualTo(StoreStatus.ACTIVE);
		assertThat(result.content().get(0).imageUrl()).isEqualTo("stores/1/thumbnail.jpg");
	}

	@Test
	@DisplayName("getSubscribedStores: 이미지가 없으면 imageUrl은 null이다")
	void getSubscribedStores_returnsNullImageUrl_whenNoImages() {
		Member member = mock(Member.class);
		PageRequest pageable = PageRequest.of(0, 10);

		Store store = mock(Store.class);
		given(store.getId()).willReturn(1L);
		given(store.getName()).willReturn("뚜레쥬르");
		given(store.getAddress()).willReturn("서울시 강남구 테헤란로 1");
		given(store.getStatus()).willReturn(StoreStatus.ACTIVE);

		SubscribedStore subscribedStore = mock(SubscribedStore.class);
		given(subscribedStore.getStore()).willReturn(store);

		given(subscribedStoreRepository.findAllByMemberOrderByCreatedAtDesc(member, pageable))
			.willReturn(new SliceImpl<>(List.of(subscribedStore), pageable, false));
		given(storeImageFileRepository.findByStoreId(1L)).willReturn(List.of());

		SliceResponse<SubscribedStoreResponse> result = storeService.getSubscribedStores(member, pageable);

		assertThat(result.content()).hasSize(1);
		assertThat(result.content().get(0).imageUrl()).isNull();
	}

	@Test
	@DisplayName("getSubscribedStores: 구독 가게 없으면 빈 content를 반환한다")
	void getSubscribedStores_returnsEmptyList_whenNoSubscriptions() {
		Member member = mock(Member.class);
		PageRequest pageable = PageRequest.of(0, 10);

		given(subscribedStoreRepository.findAllByMemberOrderByCreatedAtDesc(member, pageable))
			.willReturn(new SliceImpl<>(List.of(), pageable, false));

		SliceResponse<SubscribedStoreResponse> result = storeService.getSubscribedStores(member, pageable);

		assertThat(result.content()).isEmpty();
		assertThat(result.hasNext()).isFalse();
	}
}

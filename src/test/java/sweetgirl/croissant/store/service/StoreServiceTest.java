package sweetgirl.croissant.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;

import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.store.dto.request.KakaoStoreRegisterRequest;
import sweetgirl.croissant.store.dto.response.KakaoStoreRegisterResponse;
import sweetgirl.croissant.store.dto.response.SubscribedStoreResponse;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreImageFile;
import sweetgirl.croissant.store.entity.SubscribedStore;
import sweetgirl.croissant.store.enums.StoreStatus;
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

	@Mock
	MemberReader memberReader;

	@InjectMocks
	StoreService storeService;

	@Test
	@DisplayName("getSubscribedStores: 구독 가게 목록을 SliceResponse로 반환한다")
	void getSubscribedStores_returnsDtoList() {
		Long memberId = 1L;
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

		given(memberReader.getMember(memberId)).willReturn(member);
		given(subscribedStoreRepository.findAllByMemberOrderByCreatedAtDesc(member, pageable))
			.willReturn(new SliceImpl<>(List.of(subscribedStore), pageable, false));
		given(storeImageFileRepository.findByStoreIdOrderByDisplayOrderAscIdAsc(1L)).willReturn(List.of(image));

		SliceResponse<SubscribedStoreResponse> result = storeService.getSubscribedStores(memberId, pageable);

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
		Long memberId = 1L;
		Member member = mock(Member.class);
		PageRequest pageable = PageRequest.of(0, 10);

		Store store = mock(Store.class);
		given(store.getId()).willReturn(1L);
		given(store.getName()).willReturn("뚜레쥬르");
		given(store.getAddress()).willReturn("서울시 강남구 테헤란로 1");
		given(store.getStatus()).willReturn(StoreStatus.ACTIVE);

		SubscribedStore subscribedStore = mock(SubscribedStore.class);
		given(subscribedStore.getStore()).willReturn(store);

		given(memberReader.getMember(memberId)).willReturn(member);
		given(subscribedStoreRepository.findAllByMemberOrderByCreatedAtDesc(member, pageable))
			.willReturn(new SliceImpl<>(List.of(subscribedStore), pageable, false));
		given(storeImageFileRepository.findByStoreIdOrderByDisplayOrderAscIdAsc(1L)).willReturn(List.of());

		SliceResponse<SubscribedStoreResponse> result = storeService.getSubscribedStores(memberId, pageable);

		assertThat(result.content()).hasSize(1);
		assertThat(result.content().get(0).imageUrl()).isNull();
	}

	@Test
	@DisplayName("getSubscribedStores: 구독 가게 없으면 빈 content를 반환한다")
	void getSubscribedStores_returnsEmptyList_whenNoSubscriptions() {
		Long memberId = 1L;
		Member member = mock(Member.class);
		PageRequest pageable = PageRequest.of(0, 10);

		given(memberReader.getMember(memberId)).willReturn(member);
		given(subscribedStoreRepository.findAllByMemberOrderByCreatedAtDesc(member, pageable))
			.willReturn(new SliceImpl<>(List.of(), pageable, false));

		SliceResponse<SubscribedStoreResponse> result = storeService.getSubscribedStores(memberId, pageable);

		assertThat(result.content()).isEmpty();
		assertThat(result.hasNext()).isFalse();
	}

	@Test
	@DisplayName("registerKakaoStore: 카카오 ID가 달라도 같은 이름과 주소의 기존 가게를 재사용한다")
	void registerKakaoStore_reusesExistingStoreBySameNameAndAddress() {
		KakaoStoreRegisterRequest request = new KakaoStoreRegisterRequest(
			"1880963204",
			"파티세리영원",
			"서울 마포구 숭문4길 6",
			null,
			"",
			"126.94569719881",
			"37.547596273407");

		Store existingStore = mock(Store.class);
		given(existingStore.getId()).willReturn(451L);
		given(existingStore.getAddress()).willReturn("서울 마포구 숭문4길 6 1층");

		given(storeRepository.findByKakaoPlaceId("1880963204")).willReturn(Optional.empty());
		given(storeRepository.findActiveStoresByCompactName("파티세리영원"))
			.willReturn(List.of(existingStore));

		KakaoStoreRegisterResponse result = storeService.registerKakaoStore(request);

		assertThat(result.storeId()).isEqualTo(451L);
		verify(storeRepository).updateKakaoPlaceMetadata(
			eq(451L),
			eq("1880963204"),
			eq(""),
			eq(126.94569719881),
			eq(37.547596273407));
		verify(storeRepository, never()).insertIfNotExistsByKakaoPlaceId(
			anyString(),
			anyString(),
			any(),
			any(),
			any(),
			anyDouble(),
			anyDouble(),
			any(),
			anyString());
	}
}

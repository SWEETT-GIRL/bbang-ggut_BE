package sweetgirl.croissant.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

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
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.dto.response.StoreDetailResponse;
import sweetgirl.croissant.store.dto.response.SubscribedStoreResponse;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreImageFile;
import sweetgirl.croissant.store.entity.SubscribedStore;
import sweetgirl.croissant.store.enums.StoreStatus;
import sweetgirl.croissant.store.repository.SavedStoreRepository;
import sweetgirl.croissant.store.repository.StoreBusinessHourRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreRepository;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;

@ExtendWith(MockitoExtension.class)
class StoreQueryServiceTest {

	@Mock
	StoreRepository storeRepository;

	@Mock
	StoreBusinessHourRepository storeBusinessHourRepository;

	@Mock
	MenuImageFileRepository menuImageFileRepository;

	@Mock
	MenuRepository menuRepository;

	@Mock
	StoreImageFileRepository storeImageFileRepository;

	@Mock
	SubscribedStoreRepository subscribedStoreRepository;

	@Mock
	SavedStoreRepository savedStoreRepository;

	@Mock
	MemberReader memberReader;

	@InjectMocks
	StoreQueryService storeQueryService;

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
		given(image.getResizedKey320()).willReturn("resized/320/stores/1/thumbnail.jpg");

		SubscribedStore subscribedStore = mock(SubscribedStore.class);
		given(subscribedStore.getStore()).willReturn(store);

		given(memberReader.getMember(memberId)).willReturn(member);
		given(subscribedStoreRepository.findAllByMemberOrderByCreatedAtDesc(member, pageable))
			.willReturn(new SliceImpl<>(List.of(subscribedStore), pageable, false));
		given(storeImageFileRepository.findByStoreIdOrderByDisplayOrderAscIdAsc(1L)).willReturn(List.of(image));

		SliceResponse<SubscribedStoreResponse> result = storeQueryService.getSubscribedStores(memberId, pageable);

		assertThat(result.content()).hasSize(1);
		assertThat(result.hasNext()).isFalse();
		assertThat(result.content().get(0).storeId()).isEqualTo(1L);
		assertThat(result.content().get(0).storeName()).isEqualTo("뚜레쥬르");
		assertThat(result.content().get(0).address()).isEqualTo("서울시 강남구 테헤란로 1");
		assertThat(result.content().get(0).status()).isEqualTo(StoreStatus.ACTIVE);
		assertThat(result.content().get(0).thumbnailImageKey()).isEqualTo("resized/320/stores/1/thumbnail.jpg");
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

		SliceResponse<SubscribedStoreResponse> result = storeQueryService.getSubscribedStores(memberId, pageable);

		assertThat(result.content()).hasSize(1);
		assertThat(result.content().get(0).thumbnailImageKey()).isNull();
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

		SliceResponse<SubscribedStoreResponse> result = storeQueryService.getSubscribedStores(memberId, pageable);

		assertThat(result.content()).isEmpty();
		assertThat(result.hasNext()).isFalse();
	}

	// ───────────────────────── getStoreDetail isSaved ─────────────────────────

	@Test
	@DisplayName("getStoreDetail: 찜한 가게 조회 → isSaved=true")
	void getStoreDetail_savedStore_returnsIsSavedTrue() {
		Long memberId = 1L;
		Long storeId = 10L;

		Store store = mock(Store.class);
		given(storeRepository.findById(storeId)).willReturn(Optional.of(store));
		given(storeBusinessHourRepository.findByStoreIdOrderByDayOfWeekAsc(storeId)).willReturn(List.of());
		given(storeImageFileRepository.findByStoreIdOrderByDisplayOrderAscIdAsc(storeId)).willReturn(List.of());
		given(menuImageFileRepository.findByMenuStoreIdOrderByDisplayOrderAscIdAsc(storeId)).willReturn(List.of());
		given(subscribedStoreRepository.existsByMemberIdAndStoreId(memberId, storeId)).willReturn(false);
		given(savedStoreRepository.existsByMemberIdAndStoreId(memberId, storeId)).willReturn(true);

		StoreDetailResponse result = storeQueryService.getStoreDetail(memberId, storeId);

		assertThat(result.isSaved()).isTrue();
	}

	@Test
	@DisplayName("getStoreDetail: 찜 안 한 가게 조회 → isSaved=false")
	void getStoreDetail_notSavedStore_returnsIsSavedFalse() {
		Long memberId = 1L;
		Long storeId = 10L;

		Store store = mock(Store.class);
		given(storeRepository.findById(storeId)).willReturn(Optional.of(store));
		given(storeBusinessHourRepository.findByStoreIdOrderByDayOfWeekAsc(storeId)).willReturn(List.of());
		given(storeImageFileRepository.findByStoreIdOrderByDisplayOrderAscIdAsc(storeId)).willReturn(List.of());
		given(menuImageFileRepository.findByMenuStoreIdOrderByDisplayOrderAscIdAsc(storeId)).willReturn(List.of());
		given(subscribedStoreRepository.existsByMemberIdAndStoreId(memberId, storeId)).willReturn(false);
		given(savedStoreRepository.existsByMemberIdAndStoreId(memberId, storeId)).willReturn(false);

		StoreDetailResponse result = storeQueryService.getStoreDetail(memberId, storeId);

		assertThat(result.isSaved()).isFalse();
	}
}

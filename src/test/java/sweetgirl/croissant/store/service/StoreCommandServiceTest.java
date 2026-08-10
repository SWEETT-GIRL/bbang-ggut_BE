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

import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.store.dto.request.KakaoStoreRegisterRequest;
import sweetgirl.croissant.store.dto.response.KakaoStoreRegisterResponse;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.repository.StoreRepository;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;

@ExtendWith(MockitoExtension.class)
class StoreCommandServiceTest {

	@Mock
	StoreRepository storeRepository;

	@Mock
	SubscribedStoreRepository subscribedStoreRepository;

	@Mock
	MemberReader memberReader;

	@Mock
	org.springframework.context.ApplicationEventPublisher eventPublisher;

	@InjectMocks
	StoreCommandService storeCommandService;

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

		KakaoStoreRegisterResponse result = storeCommandService.registerKakaoStore(request);

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
			anyString(),
			any());
	}
}

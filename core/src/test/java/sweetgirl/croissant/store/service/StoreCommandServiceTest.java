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
import org.springframework.context.ApplicationEventPublisher;

import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.store.dto.request.KakaoStoreRegisterRequest;
import sweetgirl.croissant.store.dto.request.NaverStoreRegisterRequest;
import sweetgirl.croissant.store.dto.response.KakaoStoreRegisterResponse;
import sweetgirl.croissant.store.dto.response.NaverStoreRegisterResponse;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.event.StoreSearchNameChangedEvent;
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
	ApplicationEventPublisher eventPublisher;

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
		// 기존 가게에 카카오 ID 만 붙였다 — 이름이 그대로라 자동완성 캐시를 건드리지 않는다
		verify(eventPublisher, never()).publishEvent(any(StoreSearchNameChangedEvent.class));
	}

	@Test
	@DisplayName("registerKakaoStore: 새 가게를 넣으면 그 이름으로 자동완성 캐시 무효화 이벤트를 낸다")
	void registerKakaoStore_publishesNameWhenInserted() {
		KakaoStoreRegisterRequest request = new KakaoStoreRegisterRequest(
			"999", "폼포미베이커리", "서울 마포구 숭문4길 6", null, "", "126.9", "37.5");
		Store inserted = mock(Store.class);
		given(inserted.getId()).willReturn(900L);
		given(storeRepository.findByKakaoPlaceId("999")).willReturn(Optional.empty(), Optional.of(inserted));
		given(storeRepository.findActiveStoresByCompactName("폼포미베이커리")).willReturn(List.of());

		KakaoStoreRegisterResponse result = storeCommandService.registerKakaoStore(request);

		assertThat(result.storeId()).isEqualTo(900L);
		verify(eventPublisher).publishEvent(StoreSearchNameChangedEvent.of("폼포미베이커리"));
	}

	@Test
	@DisplayName("registerNaverStore: naverPlaceId가 null이면 place id 조회 없이 이름·주소로 등록한다")
	void registerNaverStore_withoutPlaceId_registersByNameAndAddress() {
		// 네이버 지역검색은 link에 place id가 없는 가게가 많다 — null로
		// findByNaverPlaceId를 부르면 IS NULL 쿼리가 되어 수만 행이 반환되며 터졌다.
		NaverStoreRegisterRequest request = new NaverStoreRegisterRequest(
			null,
			"소금집빵",
			"대전 동구 중앙로 1",
			"대전 동구 중앙로 1",
			"042-000-0000",
			36.3286,
			127.4229);

		Store savedStore = mock(Store.class);
		given(savedStore.getId()).willReturn(777L);
		given(savedStore.getAddress()).willReturn("대전 동구 중앙로 1");

		// 등록 전엔 없고(빈 목록), insert 후 같은 조회에서 방금 넣은 가게가 잡힌다.
		given(storeRepository.findActiveStoresByCompactName("소금집빵"))
			.willReturn(List.of(), List.of(savedStore));

		NaverStoreRegisterResponse result = storeCommandService.registerNaverStore(request);

		assertThat(result.storeId()).isEqualTo(777L);
		verify(storeRepository, never()).findByNaverPlaceId(any());
		verify(storeRepository).insertIfNotExistsByNaverPlaceId(
			eq("소금집빵"),
			eq("대전 동구 중앙로 1"),
			any(),
			any(),
			any(),
			anyDouble(),
			anyDouble(),
			any(),
			eq(null),
			any());
		verify(eventPublisher).publishEvent(StoreSearchNameChangedEvent.of("소금집빵"));
	}

	@Test
	@DisplayName("registerNaverStore: naverPlaceId가 있으면 기존 place id 매칭을 그대로 쓴다")
	void registerNaverStore_withPlaceId_reusesExistingStore() {
		NaverStoreRegisterRequest request = new NaverStoreRegisterRequest(
			"12345678",
			"소금집빵",
			"대전 동구 중앙로 1",
			"대전 동구 중앙로 1",
			null,
			36.3286,
			127.4229);

		Store existingStore = mock(Store.class);
		given(existingStore.getId()).willReturn(451L);
		given(storeRepository.findByNaverPlaceId("12345678"))
			.willReturn(Optional.of(existingStore));

		NaverStoreRegisterResponse result = storeCommandService.registerNaverStore(request);

		assertThat(result.storeId()).isEqualTo(451L);
		verify(storeRepository, never()).insertIfNotExistsByNaverPlaceId(
			anyString(), anyString(), any(), any(), any(),
			anyDouble(), anyDouble(), any(), any(), any());
	}
}

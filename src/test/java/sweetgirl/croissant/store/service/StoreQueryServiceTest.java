package sweetgirl.croissant.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;

import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.code.StoreConstants;
import sweetgirl.croissant.store.dto.projection.StoreImageThumbnailProjection;
import sweetgirl.croissant.store.dto.projection.TopSubscribedStoreProjection;
import sweetgirl.croissant.store.dto.response.StoreDetailResponse;
import sweetgirl.croissant.store.dto.response.SubscribedStoreResponse;
import sweetgirl.croissant.store.dto.response.TopSubscribedStoreResponse;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreImageFile;
import sweetgirl.croissant.store.entity.SubscribedStore;
import sweetgirl.croissant.store.enums.StoreStatus;
import sweetgirl.croissant.store.enums.TrendingSignal;
import sweetgirl.croissant.store.event.StoreTrendingSignalEvent;
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

	@Mock
	ApplicationEventPublisher eventPublisher;

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
		Point location = new GeometryFactory(new PrecisionModel(), 4326)
			.createPoint(new Coordinate(126.9780, 37.5665));
		given(store.getLocation()).willReturn(location);
		given(storeRepository.findById(storeId)).willReturn(Optional.of(store));
		given(storeBusinessHourRepository.findByStoreIdOrderByDayOfWeekAsc(storeId)).willReturn(List.of());
		given(storeImageFileRepository.findByStoreIdOrderByDisplayOrderAscIdAsc(storeId)).willReturn(List.of());
		given(menuImageFileRepository.findByMenuStoreIdAndMenuDeletedAtIsNullOrderByDisplayOrderAscIdAsc(storeId))
			.willReturn(List.of());
		given(subscribedStoreRepository.existsByMemberIdAndStoreId(memberId, storeId)).willReturn(false);
		given(savedStoreRepository.existsByMemberIdAndStoreId(memberId, storeId)).willReturn(true);

		StoreDetailResponse result = storeQueryService.getStoreDetail(memberId, storeId);

		assertThat(result.isSaved()).isTrue();
		assertThat(result.latitude()).isEqualTo(37.5665);
		assertThat(result.longitude()).isEqualTo(126.9780);
		// 로그인 회원의 상세 조회는 급부상 원재료다
		verify(eventPublisher).publishEvent(new StoreTrendingSignalEvent(memberId, storeId, TrendingSignal.VIEW));
	}

	@Test
	@DisplayName("getStoreDetail: 비로그인 조회는 급부상 신호로 세지 않는다 — 평소 수준(STORE_VIEW 적재)과 같은 기준")
	void getStoreDetail_guest_doesNotPublishTrendingSignal() {
		Long storeId = 10L;
		Store store = mock(Store.class);
		given(store.getLocation()).willReturn(new GeometryFactory(new PrecisionModel(), 4326)
			.createPoint(new Coordinate(126.9780, 37.5665)));
		given(storeRepository.findById(storeId)).willReturn(Optional.of(store));
		given(storeBusinessHourRepository.findByStoreIdOrderByDayOfWeekAsc(storeId)).willReturn(List.of());
		given(storeImageFileRepository.findByStoreIdOrderByDisplayOrderAscIdAsc(storeId)).willReturn(List.of());
		given(menuImageFileRepository.findByMenuStoreIdAndMenuDeletedAtIsNullOrderByDisplayOrderAscIdAsc(storeId))
			.willReturn(List.of());

		storeQueryService.getStoreDetail(null, storeId);

		verify(eventPublisher, never()).publishEvent(any(StoreTrendingSignalEvent.class));
	}

	@Test
	@DisplayName("getStoreDetail: 찜 안 한 가게 조회 → isSaved=false")
	void getStoreDetail_notSavedStore_returnsIsSavedFalse() {
		Long memberId = 1L;
		Long storeId = 10L;

		Store store = mock(Store.class);
		Point location = new GeometryFactory(new PrecisionModel(), 4326)
			.createPoint(new Coordinate(126.9780, 37.5665));
		given(store.getLocation()).willReturn(location);
		given(storeRepository.findById(storeId)).willReturn(Optional.of(store));
		given(storeBusinessHourRepository.findByStoreIdOrderByDayOfWeekAsc(storeId)).willReturn(List.of());
		given(storeImageFileRepository.findByStoreIdOrderByDisplayOrderAscIdAsc(storeId)).willReturn(List.of());
		given(menuImageFileRepository.findByMenuStoreIdAndMenuDeletedAtIsNullOrderByDisplayOrderAscIdAsc(storeId))
			.willReturn(List.of());
		given(subscribedStoreRepository.existsByMemberIdAndStoreId(memberId, storeId)).willReturn(false);
		given(savedStoreRepository.existsByMemberIdAndStoreId(memberId, storeId)).willReturn(false);

		StoreDetailResponse result = storeQueryService.getStoreDetail(memberId, storeId);

		assertThat(result.isSaved()).isFalse();
	}

	// ───────────────────────── getTopSubscribedStores ─────────────────────────

	@Test
	@DisplayName("getTopSubscribedStores: 저장소가 준 인기도 순서를 그대로 두고 지역·주소·썸네일을 싣고 subscribeCount 는 모두 0이다")
	void getTopSubscribedStores_keepsRepositoryOrderWithRegionAddressThumbnail() {
		List<TopSubscribedStoreProjection> topStores = List.of(
			topStore(30L, "성수 베이크", "서울", "성동구", "서울 성동구 성수동 1-1"),
			topStore(10L, "몽심", "서울", "마포구", null));
		given(storeRepository.findTopSubscribedStores(StoreConstants.TOP_SUBSCRIBED_STORE_LIMIT))
			.willReturn(topStores);
		// 헬퍼가 다른 mock 을 스텁하므로 given(...) 안에서 부르면 UnfinishedStubbing 이 난다 — 먼저 만든다.
		List<StoreImageThumbnailProjection> thumbnails = List.of(thumbnail(30L, "store/30_320.jpg"));
		given(storeImageFileRepository.findThumbnailsByStoreIdIn(List.of(30L, 10L))).willReturn(thumbnails);

		List<TopSubscribedStoreResponse> result = storeQueryService.getTopSubscribedStores();

		assertThat(result).containsExactly(
			new TopSubscribedStoreResponse(30L, "성수 베이크", "서울 성동구", "서울 성동구 성수동 1-1", "store/30_320.jpg",
				0L),
			new TopSubscribedStoreResponse(10L, "몽심", "서울 마포구", null, null, 0L));
	}

	@Test
	@DisplayName("getTopSubscribedStores: 지역이 하나만 있으면 그것만, 둘 다 없으면 빈 문자열이다 — null 이면 1.5.x 앱이 꺼진다")
	void getTopSubscribedStores_neverReturnsNullRegion() {
		List<TopSubscribedStoreProjection> topStores = List.of(
			topStore(1L, "시도만", "서울", null, null),
			topStore(2L, "구만", null, "성동구", null),
			topStore(3L, "둘 다 없음", null, null, null));
		given(storeRepository.findTopSubscribedStores(StoreConstants.TOP_SUBSCRIBED_STORE_LIMIT))
			.willReturn(topStores);

		List<TopSubscribedStoreResponse> result = storeQueryService.getTopSubscribedStores();

		assertThat(result).extracting(TopSubscribedStoreResponse::region)
			.containsExactly("서울", "성동구", "");
	}

	@Test
	@DisplayName("getTopSubscribedStores: ACTIVE 가게가 없으면 빈 목록을 반환한다")
	void getTopSubscribedStores_returnsEmptyList_whenNoStores() {
		given(storeRepository.findTopSubscribedStores(StoreConstants.TOP_SUBSCRIBED_STORE_LIMIT))
			.willReturn(List.of());

		assertThat(storeQueryService.getTopSubscribedStores()).isEmpty();
	}

	private TopSubscribedStoreProjection topStore(Long id, String name, String region1st, String region2nd,
			String address) {
		TopSubscribedStoreProjection projection = mock(TopSubscribedStoreProjection.class);
		given(projection.getId()).willReturn(id);
		given(projection.getName()).willReturn(name);
		given(projection.getRegion1st()).willReturn(region1st);
		given(projection.getRegion2nd()).willReturn(region2nd);
		given(projection.getAddress()).willReturn(address);
		return projection;
	}

	/** 320 리사이즈 키가 있는 썸네일 — 서비스는 그 키가 있으면 원본 키를 보지 않는다 */
	private StoreImageThumbnailProjection thumbnail(Long storeId, String resizedKey320) {
		StoreImageThumbnailProjection projection = mock(StoreImageThumbnailProjection.class);
		given(projection.getStoreId()).willReturn(storeId);
		given(projection.getResizedKey320()).willReturn(resizedKey320);
		return projection;
	}
}

package sweetgirl.croissant.dessert.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.common.util.MapBounds;
import sweetgirl.croissant.dessert.dto.projection.DessertStoreProjection;
import sweetgirl.croissant.dessert.dto.response.DessertStoreResponse;
import sweetgirl.croissant.dessert.dto.response.HotDessertResponse;
import sweetgirl.croissant.dessert.entity.DessertItem;
import sweetgirl.croissant.dessert.enums.DessertGrade;
import sweetgirl.croissant.dessert.enums.DessertItemType;
import sweetgirl.croissant.dessert.enums.DessertTrendStatus;
import sweetgirl.croissant.dessert.exception.DessertException;
import sweetgirl.croissant.dessert.repository.ChainStoreRepository;
import sweetgirl.croissant.dessert.repository.ConvenienceStoreRepository;
import sweetgirl.croissant.dessert.repository.DessertItemRepository;
import sweetgirl.croissant.store.dto.projection.StoreImageThumbnailProjection;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;

@ExtendWith(MockitoExtension.class)
class DessertQueryServiceTest {

	private static final double SEOUL_LAT = 37.5665;
	private static final double SEOUL_LNG = 126.978;

	@Mock
	private DessertItemRepository dessertItemRepository;

	@Mock
	private ConvenienceStoreRepository convenienceStoreRepository;

	@Mock
	private ChainStoreRepository chainStoreRepository;

	@Mock
	private StoreImageFileRepository storeImageFileRepository;

	@InjectMocks
	private DessertQueryService dessertQueryService;

	@Test
	@DisplayName("칩 줄은 뜨는 것부터 선다 — 판정이 없는 것은 맨 뒤")
	void getHotDesserts_ordersByTrendStatus() {
		DessertItem cooling = dessertItem(1L, "식는디저트", DessertItemType.GENERIC, null, DessertTrendStatus.COOLING);
		DessertItem unjudged = dessertItem(2L, "판정없음", DessertItemType.GENERIC, null, null);
		DessertItem hot = dessertItem(3L, "피자설기", DessertItemType.GENERIC, null, DessertTrendStatus.HOT);
		given(dessertItemRepository.findByActiveTrue()).willReturn(List.of(cooling, unjudged, hot));

		List<HotDessertResponse> hotDesserts = dessertQueryService.getHotDesserts();

		assertThat(hotDesserts).extracting(HotDessertResponse::name)
			.containsExactly("피자설기", "식는디저트", "판정없음");
	}

	@Test
	@DisplayName("동네 가게가 파는 디저트는 판매 가게와 썸네일을 함께 준다")
	void getSellingStores_genericJoinsThumbnail() {
		DessertItem pizzaSeolgi = dessertItem(1L, "피자설기", DessertItemType.GENERIC, null, DessertTrendStatus.HOT);
		given(dessertItemRepository.findById(1L)).willReturn(Optional.of(pizzaSeolgi));
		given(dessertItemRepository.findSellingStoresNearby(eq(1L), anyDouble(), anyDouble(), anyDouble(),
			any(Pageable.class)))
			.willReturn(List.of(sellingStore(9963L, "원조공주떡집", "STRONG", 2, LocalDate.of(2026, 9, 17), 1278)));
		given(storeImageFileRepository.findThumbnailsByStoreIdIn(List.of(9963L)))
			.willReturn(List.of(thumbnail(9963L, "aws-key", "resized-320")));

		List<DessertStoreResponse> sellingStores = dessertQueryService.getSellingStores(1L, SEOUL_LAT, SEOUL_LNG, 30,
			null);

		assertThat(sellingStores).singleElement().satisfies(sellingStore -> {
			assertThat(sellingStore.storeId()).isEqualTo(9963L);
			assertThat(sellingStore.thumbnailImageKey()).isEqualTo("resized-320");
			assertThat(sellingStore.grade()).isEqualTo(DessertGrade.STRONG);
			assertThat(sellingStore.evidenceCount()).isEqualTo(2);
			assertThat(sellingStore.distanceMeters()).isEqualTo(1278);
		});
	}

	@Test
	@DisplayName("편의점 상품은 브랜드 지점을 주고 가게 id·등급은 비운다")
	void getSellingStores_convenienceUsesBranches() {
		DessertItem gs25Item = dessertItem(3L, "GS25 한정선", DessertItemType.CONVENIENCE, "GS25",
			DessertTrendStatus.HOT);
		given(dessertItemRepository.findById(3L)).willReturn(Optional.of(gs25Item));
		given(convenienceStoreRepository.findBranchesNearby(eq("GS25"), anyDouble(), anyDouble(), anyDouble(),
			any(Pageable.class)))
			.willReturn(List.of(sellingStore(null, "무교YG타워점", null, null, null, 238)));

		List<DessertStoreResponse> branches = dessertQueryService.getSellingStores(3L, SEOUL_LAT, SEOUL_LNG, 30, null);

		assertThat(branches).singleElement().satisfies(branch -> {
			assertThat(branch.storeId()).isNull();
			assertThat(branch.grade()).isNull();
			assertThat(branch.thumbnailImageKey()).isNull();
			assertThat(branch.brand()).isEqualTo("GS25");
		});
		// 편의점 지점은 빵긋 가게가 아니라 가게 사진을 물을 데가 없다
		verify(storeImageFileRepository, never()).findThumbnailsByStoreIdIn(anyList());
	}

	@Test
	@DisplayName("체인 한정 메뉴는 그 브랜드 지점을 준다 — 표만 다르고 편의점과 같은 길이다")
	void getSellingStores_chainUsesBranches() {
		DessertItem churros = dessertItem(5L, "초코츄러스", DessertItemType.CHAIN, "파리바게뜨",
			DessertTrendStatus.HOT);
		given(dessertItemRepository.findById(5L)).willReturn(Optional.of(churros));
		given(chainStoreRepository.findBranchesNearby(eq("파리바게뜨"), anyDouble(), anyDouble(), anyDouble(),
			any(Pageable.class)))
			.willReturn(List.of(sellingStore(null, "파리바게뜨 시청역점", null, null, null, 180)));

		List<DessertStoreResponse> branches = dessertQueryService.getSellingStores(5L, SEOUL_LAT, SEOUL_LNG, 30, null);

		assertThat(branches).singleElement().satisfies(branch -> {
			assertThat(branch.name()).isEqualTo("파리바게뜨 시청역점");
			assertThat(branch.storeId()).isNull();
			assertThat(branch.grade()).isNull();
			assertThat(branch.brand()).isEqualTo("파리바게뜨");
		});
		// 체인은 dessert_item_store 를 쓰지 않는다 — 그 표는 GENERIC 전용이다
		verify(dessertItemRepository, never())
			.findSellingStoresNearby(anyLong(), anyDouble(), anyDouble(), anyDouble(), any(Pageable.class));
	}

	@Test
	@DisplayName("요청한 개수가 상한을 넘으면 상한으로 자른다 — 편의점 5만 곳이 핀으로 쏟아지면 안 된다")
	void getSellingStores_capsSize() {
		DessertItem pizzaSeolgi = dessertItem(1L, "피자설기", DessertItemType.GENERIC, null, DessertTrendStatus.HOT);
		given(dessertItemRepository.findById(1L)).willReturn(Optional.of(pizzaSeolgi));
		given(dessertItemRepository.findSellingStoresNearby(anyLong(), anyDouble(), anyDouble(), anyDouble(),
			any(Pageable.class)))
			.willReturn(List.of());

		dessertQueryService.getSellingStores(1L, SEOUL_LAT, SEOUL_LNG, 9999, null);

		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		verify(dessertItemRepository).findSellingStoresNearby(anyLong(), anyDouble(), anyDouble(), anyDouble(),
			pageable.capture());
		assertThat(pageable.getValue().getPageSize()).isEqualTo(DessertQueryService.MAX_STORE_SIZE);
	}

	@Test
	@DisplayName("반경이 상한(50km)을 넘으면 상한으로 줄인다 — 비로그인 엔드포인트라 전국을 재지 않게")
	void getSellingStores_capsRadius() {
		DessertItem pizzaSeolgi = dessertItem(1L, "피자설기", DessertItemType.GENERIC, null, DessertTrendStatus.HOT);
		given(dessertItemRepository.findById(1L)).willReturn(Optional.of(pizzaSeolgi));
		given(dessertItemRepository.findSellingStoresNearby(anyLong(), anyDouble(), anyDouble(), anyDouble(),
			any(Pageable.class)))
			.willReturn(List.of());

		dessertQueryService.getSellingStores(1L, SEOUL_LAT, SEOUL_LNG, 30, 20_000_000.0);

		verify(dessertItemRepository).findSellingStoresNearby(anyLong(), anyDouble(), anyDouble(),
			eq(DessertQueryService.MAX_RADIUS_METERS), any(Pageable.class));
	}

	@Test
	@DisplayName("좌표가 WGS84 범위 밖이면 PostGIS 500 이 아니라 400 으로 먼저 막는다")
	void getSellingStores_rejectsOutOfRangeCoordinate() {
		assertThatThrownBy(() -> dessertQueryService.getSellingStores(1L, 95.0, SEOUL_LNG, 30, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> dessertQueryService.getSellingStoresInBounds(1L,
			new MapBounds(37.5, 126.9, 37.6, 127.1), SEOUL_LAT, 200.0, null))
			.isInstanceOf(IllegalArgumentException.class);
		verify(dessertItemRepository, never()).findById(anyLong());
	}

	@Test
	@DisplayName("없는 디저트를 물으면 404")
	void getSellingStores_throwsWhenDessertMissing() {
		given(dessertItemRepository.findById(404L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> dessertQueryService.getSellingStores(404L, SEOUL_LAT, SEOUL_LNG, 30, null))
			.isInstanceOf(DessertException.class)
			.hasMessageContaining("디저트를 찾을 수 없습니다.");
	}

	@Test
	@DisplayName("브랜드 지점을 봐야 하는데 브랜드가 없으면 조용히 빈 목록을 주지 않고 멈춘다")
	void getSellingStores_throwsWhenConvenienceBrandMissing() {
		DessertItem brandless = dessertItem(5L, "브랜드없는편의점상품", DessertItemType.CONVENIENCE, null,
			DessertTrendStatus.HOT);
		given(dessertItemRepository.findById(5L)).willReturn(Optional.of(brandless));

		assertThatThrownBy(() -> dessertQueryService.getSellingStores(5L, SEOUL_LAT, SEOUL_LNG, 30, null))
			.isInstanceOf(DessertException.class);
	}

	@Test
	@DisplayName("영역을 주면 동네 가게를 그 영역 안에서 찾고, 기본 150곳까지 세운다")
	void getSellingStoresInBounds_genericUsesBounds() {
		MapBounds bounds = new MapBounds(37.5, 126.9, 37.6, 127.1);
		DessertItem pizzaSeolgi = dessertItem(1L, "피자설기", DessertItemType.GENERIC, null, DessertTrendStatus.HOT);
		given(dessertItemRepository.findById(1L)).willReturn(Optional.of(pizzaSeolgi));
		given(dessertItemRepository.findSellingStoresInBounds(eq(1L), eq(37.5), eq(126.9), eq(37.6), eq(127.1),
			eq(SEOUL_LAT), eq(SEOUL_LNG), any(Pageable.class)))
			.willReturn(List.of(sellingStore(9963L, "원조공주떡집", "STRONG", 2, LocalDate.of(2026, 9, 17), 1278)));
		given(storeImageFileRepository.findThumbnailsByStoreIdIn(List.of(9963L)))
			.willReturn(List.of(thumbnail(9963L, "aws-key", "resized-320")));

		List<DessertStoreResponse> sellingStores = dessertQueryService.getSellingStoresInBounds(1L, bounds,
			SEOUL_LAT, SEOUL_LNG, null);

		assertThat(sellingStores).singleElement().satisfies(sellingStore -> {
			assertThat(sellingStore.storeId()).isEqualTo(9963L);
			assertThat(sellingStore.thumbnailImageKey()).isEqualTo("resized-320");
		});
		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		verify(dessertItemRepository).findSellingStoresInBounds(anyLong(), anyDouble(), anyDouble(), anyDouble(),
			anyDouble(), anyDouble(), anyDouble(), pageable.capture());
		assertThat(pageable.getValue().getPageSize()).isEqualTo(DessertQueryService.DEFAULT_BOUNDS_STORE_SIZE);
		verify(dessertItemRepository, never())
			.findSellingStoresNearby(anyLong(), anyDouble(), anyDouble(), anyDouble(), any(Pageable.class));
	}

	@Test
	@DisplayName("영역으로 찾는 편의점 상품은 그 영역 안 브랜드 지점을 주고, 개수는 300에서 자른다")
	void getSellingStoresInBounds_convenienceCapsSize() {
		MapBounds bounds = new MapBounds(37.5, 126.9, 37.6, 127.1);
		DessertItem gs25Item = dessertItem(3L, "GS25 한정선", DessertItemType.CONVENIENCE, "GS25",
			DessertTrendStatus.HOT);
		given(dessertItemRepository.findById(3L)).willReturn(Optional.of(gs25Item));
		given(convenienceStoreRepository.findBranchesInBounds(eq("GS25"), anyDouble(), anyDouble(), anyDouble(),
			anyDouble(), anyDouble(), anyDouble(), any(Pageable.class)))
			.willReturn(List.of(sellingStore(null, "무교YG타워점", null, null, null, 238)));

		List<DessertStoreResponse> branches = dessertQueryService.getSellingStoresInBounds(3L, bounds,
			SEOUL_LAT, SEOUL_LNG, 9999);

		assertThat(branches).singleElement().satisfies(branch -> assertThat(branch.brand()).isEqualTo("GS25"));
		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		verify(convenienceStoreRepository).findBranchesInBounds(eq("GS25"), anyDouble(), anyDouble(), anyDouble(),
			anyDouble(), anyDouble(), anyDouble(), pageable.capture());
		assertThat(pageable.getValue().getPageSize()).isEqualTo(DessertQueryService.MAX_BOUNDS_STORE_SIZE);
	}

	@Test
	@DisplayName("영역으로 찾는 체인 한정 메뉴는 그 영역 안 브랜드 지점을 준다")
	void getSellingStoresInBounds_chainUsesBranches() {
		MapBounds bounds = new MapBounds(37.5, 126.9, 37.6, 127.1);
		DessertItem churros = dessertItem(5L, "초코츄러스", DessertItemType.CHAIN, "파리바게뜨",
			DessertTrendStatus.HOT);
		given(dessertItemRepository.findById(5L)).willReturn(Optional.of(churros));
		given(chainStoreRepository.findBranchesInBounds(eq("파리바게뜨"), anyDouble(), anyDouble(), anyDouble(),
			anyDouble(), anyDouble(), anyDouble(), any(Pageable.class)))
			.willReturn(List.of(sellingStore(null, "파리바게뜨 시청역점", null, null, null, 180)));

		List<DessertStoreResponse> branches = dessertQueryService.getSellingStoresInBounds(5L, bounds,
			SEOUL_LAT, SEOUL_LNG, 150);

		assertThat(branches).singleElement()
			.satisfies(branch -> assertThat(branch.name()).isEqualTo("파리바게뜨 시청역점"));
	}

	@Test
	@DisplayName("영역으로 찾아도 없는 디저트는 404")
	void getSellingStoresInBounds_throwsWhenDessertMissing() {
		given(dessertItemRepository.findById(404L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> dessertQueryService.getSellingStoresInBounds(404L,
			new MapBounds(37.5, 126.9, 37.6, 127.1), SEOUL_LAT, SEOUL_LNG, null))
			.isInstanceOf(DessertException.class);
	}

	/** BE 는 이 엔티티를 만들지 않는다(파이프라인이 쓴다). 테스트만 쓰자고 빌더를 열 이유가 없어 여기서 세운다 */
	private static DessertItem dessertItem(Long id, String name, DessertItemType itemType, String brand,
			DessertTrendStatus trendStatus) {
		DessertItem dessertItem = BeanUtils.instantiateClass(DessertItem.class);
		ReflectionTestUtils.setField(dessertItem, "id", id);
		ReflectionTestUtils.setField(dessertItem, "name", name);
		ReflectionTestUtils.setField(dessertItem, "itemType", itemType);
		ReflectionTestUtils.setField(dessertItem, "brand", brand);
		ReflectionTestUtils.setField(dessertItem, "trendStatus", trendStatus);
		ReflectionTestUtils.setField(dessertItem, "active", true);
		return dessertItem;
	}

	private static DessertStoreProjection sellingStore(Long storeId, String name, String grade, Integer evidenceCount,
			LocalDate lastEvidenceAt, double distanceMeters) {
		return new DessertStoreProjection() {
			@Override
			public Long getStoreId() {
				return storeId;
			}

			@Override
			public String getName() {
				return name;
			}

			@Override
			public String getAddress() {
				return "서울 중구 어딘가";
			}

			@Override
			public double getLatitude() {
				return SEOUL_LAT;
			}

			@Override
			public double getLongitude() {
				return SEOUL_LNG;
			}

			@Override
			public double getDistanceMeters() {
				return distanceMeters;
			}

			@Override
			public String getGrade() {
				return grade;
			}

			@Override
			public Integer getEvidenceCount() {
				return evidenceCount;
			}

			@Override
			public LocalDate getLastEvidenceAt() {
				return lastEvidenceAt;
			}
		};
	}

	private static StoreImageThumbnailProjection thumbnail(Long storeId, String awsKey, String resizedKey320) {
		return new StoreImageThumbnailProjection() {
			@Override
			public Long getStoreId() {
				return storeId;
			}

			@Override
			public String getAwsKey() {
				return awsKey;
			}

			@Override
			public String getResizedKey320() {
				return resizedKey320;
			}
		};
	}
}

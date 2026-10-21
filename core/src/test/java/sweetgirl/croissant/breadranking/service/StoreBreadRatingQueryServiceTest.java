package sweetgirl.croissant.breadranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.breadranking.dto.response.StoreBreadRatingResponse;
import sweetgirl.croissant.breadranking.dto.response.StoreBreadRatingResponse.TextureStat;
import sweetgirl.croissant.breadranking.entity.BreadCategory;
import sweetgirl.croissant.breadranking.enums.BreadGroup;
import sweetgirl.croissant.breadranking.repository.BreadBoardJdbcRepository;
import sweetgirl.croissant.breadranking.repository.BreadBoardJdbcRepository.MenuPriceRow;
import sweetgirl.croissant.breadranking.repository.BreadBoardJdbcRepository.StoreCategoryRow;
import sweetgirl.croissant.breadranking.repository.BreadBoardJdbcRepository.TextureStatRow;
import sweetgirl.croissant.menu.enums.MenuKind;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.enums.StoreStatus;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreRepository;

@ExtendWith(MockitoExtension.class)
class StoreBreadRatingQueryServiceTest {

	private static final Long STORE_ID = 10L;

	@Mock
	private BreadBoardJdbcRepository boardRepository;
	@Mock
	private BreadBoardQueryService boardQueryService;
	@Mock
	private StoreRepository storeRepository;

	@InjectMocks
	private StoreBreadRatingQueryService service;

	@Test
	@DisplayName("없거나 지워진 가게는 404")
	void storeNotFound() {
		given(storeRepository.findById(STORE_ID)).willReturn(Optional.of(store(StoreStatus.DELETED)));

		assertThatThrownBy(() -> service.getStoreRatings(STORE_ID)).isInstanceOf(StoreException.class);
	}

	@Test
	@DisplayName("평가한 사람 5명 이상인 종류가 없으면 빈 목록")
	void emptyWhenNoStats() {
		given(storeRepository.findById(STORE_ID)).willReturn(Optional.of(store(StoreStatus.ACTIVE)));
		given(boardRepository.findStoreCategoryStats(STORE_ID, 5)).willReturn(List.of());

		assertThat(service.getStoreRatings(STORE_ID)).isEmpty();
	}

	@Test
	@DisplayName("종류별로 전국 등수·또 먹을래요 %·식감 상위 3(비율)·대표 메뉴 가격을 붙인다")
	void buildsRatings() {
		BreadCategory salt = category(1L, MenuKind.소금빵);
		given(storeRepository.findById(STORE_ID)).willReturn(Optional.of(store(StoreStatus.ACTIVE)));
		given(boardRepository.findStoreCategoryStats(STORE_ID, 5))
			.willReturn(List.of(new StoreCategoryRow(1L, 6, 4, 5)));
		given(boardQueryService.categoriesOf(List.of(1L))).willReturn(Map.of(1L, salt));
		given(boardRepository.findStoreTextureStats(STORE_ID, List.of(1L))).willReturn(List.of(
			new TextureStatRow(1L, "바삭", 4, 5), new TextureStatRow(1L, "촉촉", 2, 5),
			new TextureStatRow(1L, "없는칩", 2, 5), new TextureStatRow(1L, "쫀득", 1, 5),
			new TextureStatRow(1L, "폭신", 1, 5)));
		given(boardRepository.findStoreMenuPrices(STORE_ID, List.of(1L)))
			.willReturn(List.of(new MenuPriceRow(1L, 100L, 3800)));
		given(boardQueryService.nationalRankOf(salt, STORE_ID)).willReturn(2);

		List<StoreBreadRatingResponse> ratings = service.getStoreRatings(STORE_ID);

		assertThat(ratings).hasSize(1);
		StoreBreadRatingResponse rating = ratings.get(0);
		assertThat(rating.category().name()).isEqualTo("소금빵");
		assertThat(rating.rank()).isEqualTo(2);
		assertThat(rating.raters()).isEqualTo(6);
		assertThat(rating.againRate()).isEqualTo(0.8);
		assertThat(rating.textures()).extracting(TextureStat::code).containsExactly("바삭", "촉촉", "쫀득");
		assertThat(rating.textures().get(0).ratio()).isEqualTo(0.8);
		assertThat(rating.textures().get(0).label()).isEqualTo("바삭");
		assertThat(rating.menuId()).isEqualTo(100L);
		assertThat(rating.price()).isEqualTo(3800);
	}

	private static BreadCategory category(Long id, MenuKind kind) {
		BreadCategory category = BreadCategory.custom(kind.name(), BreadGroup.빵, null);
		ReflectionTestUtils.setField(category, "id", id);
		ReflectionTestUtils.setField(category, "menuKind", kind);
		return category;
	}

	private static Store store(StoreStatus status) {
		Store store = Store.builder().name("밀도 성수").address("서울 성동구").build();
		ReflectionTestUtils.setField(store, "id", STORE_ID);
		ReflectionTestUtils.setField(store, "status", status);
		return store;
	}
}

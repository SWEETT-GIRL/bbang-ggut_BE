package sweetgirl.croissant.breadranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.breadranking.dto.response.BreadBoardEntryResponse;
import sweetgirl.croissant.breadranking.dto.response.BreadBoardResponse;
import sweetgirl.croissant.breadranking.dto.response.BreadCategoryResponse;
import sweetgirl.croissant.breadranking.entity.BreadCategory;
import sweetgirl.croissant.breadranking.enums.BreadGroup;
import sweetgirl.croissant.breadranking.repository.BreadBoardJdbcRepository;
import sweetgirl.croissant.breadranking.repository.BreadBoardJdbcRepository.BoardRow;
import sweetgirl.croissant.breadranking.repository.BreadBoardJdbcRepository.CategoryVotersRow;
import sweetgirl.croissant.breadranking.repository.BreadCategoryRepository;
import sweetgirl.croissant.menu.enums.MenuKind;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.repository.StoreRepository;
import sweetgirl.croissant.store.service.StoreThumbnailReader;

@ExtendWith(MockitoExtension.class)
class BreadBoardQueryServiceTest {

	private static final Long CATEGORY_ID = 1L;
	private static final LocalDateTime VOTED_AT = LocalDateTime.of(2026, 9, 30, 12, 0);

	@Mock
	private BreadBoardJdbcRepository boardRepository;
	@Mock
	private BreadCategoryRepository categoryRepository;
	@Mock
	private BreadCategoryQueryService categoryQueryService;
	@Mock
	private StoreRepository storeRepository;
	@Mock
	private StoreThumbnailReader thumbnailReader;
	@Mock
	private RankChangeResolver rankChangeResolver;

	@InjectMocks
	private BreadBoardQueryService service;

	@Test
	@DisplayName("탭 목록 — 10명 이상 카테고리를 리포지토리 순서(사람 수)대로, 사람 수를 실어서")
	void getBoardCategories() {
		BreadCategory salt = category(1L, MenuKind.소금빵);
		BreadCategory bagel = category(2L, MenuKind.베이글);
		given(boardRepository.findCategoriesByVoters(10)).willReturn(List.of(
			new CategoryVotersRow(1L, 48, 60), new CategoryVotersRow(2L, 12, 12)));
		given(categoryRepository.findAllById(List.of(1L, 2L))).willReturn(List.of(bagel, salt));

		List<BreadCategoryResponse> categories = service.getBoardCategories();

		assertThat(categories).extracting(BreadCategoryResponse::name).containsExactly("소금빵", "베이글");
		assertThat(categories.get(0).voters()).isEqualTo(48);
	}

	@Test
	@DisplayName("전체 3표 미만이면 목록을 비우고 모자란 표 수를 준다")
	void getBoard_belowMinimumVotes() {
		BreadCategory salt = category(CATEGORY_ID, MenuKind.소금빵);
		given(categoryQueryService.getCategory(CATEGORY_ID)).willReturn(salt);
		given(boardRepository.findBoard(CATEGORY_ID, "소금빵", 50)).willReturn(List.of(
			new BoardRow(10L, 2, VOTED_AT, 0, 0, null, 2)));
		given(boardRepository.countVoters(CATEGORY_ID)).willReturn(2L);

		BreadBoardResponse response = service.getBoard(CATEGORY_ID, null);

		assertThat(response.entries()).isEmpty();
		assertThat(response.totalVotes()).isEqualTo(2);
		assertThat(response.missingVotes()).isEqualTo(1);
		assertThat(response.category().voters()).isEqualTo(2);
	}

	@Test
	@DisplayName("열린 판 — 순위 번호·사람 수·또 먹을래요 %·대표 식감·어제 대비 변동을 줄마다 붙인다")
	void getBoard_buildsEntries() {
		BreadCategory salt = category(CATEGORY_ID, MenuKind.소금빵);
		given(categoryQueryService.getCategory(CATEGORY_ID)).willReturn(salt);
		given(boardRepository.findBoard(CATEGORY_ID, "소금빵", 100)).willReturn(List.of(
			new BoardRow(10L, 5, VOTED_AT, 3, 4, "겉바속촉", 8),
			new BoardRow(20L, 3, VOTED_AT, 0, 0, null, 8)));
		given(boardRepository.countVoters(CATEGORY_ID)).willReturn(7L);
		given(storeRepository.findAllById(List.of(10L, 20L))).willReturn(List.of(store(10L), store(20L)));
		given(thumbnailReader.thumbnailsOf(List.of(10L, 20L))).willReturn(Map.of(10L, "resized/320/a"));
		given(rankChangeResolver.resolve(eq("BREAD"), eq(1L), eq(""), any()))
			.willReturn(RankChanges.of(Map.of(10L, 2, 30L, 1)));

		BreadBoardResponse response = service.getBoard(CATEGORY_ID, 500);

		assertThat(response.missingVotes()).isZero();
		assertThat(response.entries()).hasSize(2);
		BreadBoardEntryResponse first = response.entries().get(0);
		assertThat(first.rank()).isEqualTo(1);
		assertThat(first.store().thumbnailImageKey()).isEqualTo("resized/320/a");
		assertThat(first.voters()).isEqualTo(5);
		assertThat(first.againRate()).isEqualTo(0.75);
		assertThat(first.topTexture().code()).isEqualTo("겉바속촉");
		assertThat(first.rankChange()).isEqualTo(1);
		assertThat(first.isNew()).isFalse();
		BreadBoardEntryResponse second = response.entries().get(1);
		assertThat(second.rank()).isEqualTo(2);
		assertThat(second.againRate()).isNull();
		assertThat(second.topTexture()).isNull();
		assertThat(second.rankChange()).isNull();
		assertThat(second.isNew()).isTrue();
	}

	@Test
	@DisplayName("limit 은 1~100 으로 자른다")
	void getBoard_clampsLimit() {
		BreadCategory salt = category(CATEGORY_ID, MenuKind.소금빵);
		given(categoryQueryService.getCategory(CATEGORY_ID)).willReturn(salt);
		given(boardRepository.findBoard(anyLong(), anyString(), eq(1))).willReturn(List.of());

		service.getBoard(CATEGORY_ID, 0);

		verify(boardRepository).findBoard(CATEGORY_ID, "소금빵", 1);
	}

	@Test
	@DisplayName("전국 등수 — 판에서 그 가게의 자리, 안 열렸거나 없으면 null")
	void nationalRankOf() {
		BreadCategory salt = category(CATEGORY_ID, MenuKind.소금빵);
		given(boardRepository.findBoard(CATEGORY_ID, "소금빵", 100)).willReturn(List.of(
			new BoardRow(10L, 5, VOTED_AT, 0, 0, null, 8), new BoardRow(20L, 3, VOTED_AT, 0, 0, null, 8)));

		assertThat(service.nationalRankOf(salt, 20L)).isEqualTo(2);
		assertThat(service.nationalRankOf(salt, 99L)).isNull();

		given(boardRepository.findBoard(CATEGORY_ID, "소금빵", 100)).willReturn(List.of(
			new BoardRow(10L, 2, VOTED_AT, 0, 0, null, 2)));
		assertThat(service.nationalRankOf(salt, 10L)).isNull();
	}

	private static BreadCategory category(Long id, MenuKind kind) {
		BreadCategory category = BreadCategory.custom(kind.name(), BreadGroup.빵, null);
		ReflectionTestUtils.setField(category, "id", id);
		ReflectionTestUtils.setField(category, "menuKind", kind);
		return category;
	}

	private static Store store(Long id) {
		Store store = Store.builder().name("가게 " + id).address("서울 성동구").region1st("서울").region2nd("성동구").build();
		ReflectionTestUtils.setField(store, "id", id);
		return store;
	}
}

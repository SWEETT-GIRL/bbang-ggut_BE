package sweetgirl.croissant.store.service.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static sweetgirl.croissant.store.code.StoreConstants.TOP_MENU_LIMIT;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import sweetgirl.croissant.config.persistence.JpaConfig;
import sweetgirl.croissant.menu.dto.dto.MenuSummaryDto;
import sweetgirl.croissant.menu.dto.projection.MenuImageThumbnailProjection;
import sweetgirl.croissant.menu.dto.projection.StoreMenuProjection;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.dto.projection.StoreImageThumbnailProjection;
import sweetgirl.croissant.store.dto.response.StoreSearchResponse;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreRepository;

/**
 * 조립기가 사진을 "응답에 실리는 만큼만" 읽도록 바꾼 뒤에도 응답이 이전 구현(가게·메뉴의 사진을 전부 읽고 자바에서 첫 장 고르기)과
 * 같은지 본다. 메뉴 조회와 메뉴를 고르는 기준은 바꾸지 않았다.
 * 메뉴·사진 쿼리는 실제 H2 에서 돌리고, 가게 테이블은 PostGIS 컬럼 때문에 H2 에 없어 가게 조회만 목으로 둔다.
 */
@DataJpaTest
@Import(JpaConfig.class)
class StoreSearchAssemblerEquivalenceTest {

	private static final long MANY_MENUS_STORE = 101L;
	private static final long FEW_MENUS_STORE = 102L;
	private static final long EMPTY_STORE = 103L;
	private static final long DELETED_ONLY_STORE = 104L;
	private static final long TIED_LIKES_STORE = 105L;

	@Autowired
	private MenuRepository menuRepository;

	@Autowired
	private MenuImageFileRepository menuImageFileRepository;

	@Autowired
	private StoreImageFileRepository storeImageFileRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private StoreRepository storeRepository;
	private MenuImageFileRepository recordingMenuImageFileRepository;
	private StoreSearchAssembler assembler;

	@BeforeEach
	void setUp() {
		// 가게 테이블이 없으니(H2) 메뉴·사진의 store_id FK 는 검사하지 않는다
		jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
		storeRepository = mock(StoreRepository.class);
		// 실제 쿼리를 그대로 돌리되, 썸네일 조회에 어떤 메뉴 ID 가 넘어갔는지 볼 수 있게 감싼다
		recordingMenuImageFileRepository = mock(MenuImageFileRepository.class, delegatesTo(menuImageFileRepository));
		assembler = new StoreSearchAssembler(storeRepository, storeImageFileRepository,
			recordingMenuImageFileRepository, menuRepository);
	}

	@Test
	@DisplayName("메뉴 5개 초과·사진 여러 장·삭제된 메뉴·처리 중 사진이 섞여도 이전 구현과 같은 응답")
	void assemble_matchesLegacyResult() {
		// 메뉴 8개 — 좋아요가 전부 달라 상위 5개가 한 가지로 정해진다. 좋아요 1등은 삭제된 메뉴다.
		long deletedTop = insertMenu(MANY_MENUS_STORE, "삭제된 인기 메뉴", 99, true);
		long first = insertMenu(MANY_MENUS_STORE, "소금빵", 50, false);
		insertMenu(MANY_MENUS_STORE, "휘낭시에", 10, false);
		long second = insertMenu(MANY_MENUS_STORE, "크루아상", 40, false);
		long third = insertMenu(MANY_MENUS_STORE, "에그타르트", 30, false);
		insertMenu(MANY_MENUS_STORE, "스콘", 5, false);
		long fourth = insertMenu(MANY_MENUS_STORE, "바게트", 20, false);
		long fifth = insertMenu(MANY_MENUS_STORE, "치아바타", 15, false);
		insertMenu(MANY_MENUS_STORE, "마들렌", 1, false);
		// 사진 여러 장: displayOrder 가 앞선 사진이 나중에 들어왔고, 맨 앞 순서의 사진은 처리 중이다
		insertMenuImage(first, 2, "menu/first-2.jpg", "menu/first-2_320.jpg", "DONE");
		insertMenuImage(first, 1, "menu/first-1.jpg", "menu/first-1_320.jpg", "DONE");
		insertMenuImage(first, 0, "menu/first-pending.jpg", "menu/first-pending_320.jpg", "PENDING");
		// displayOrder 가 같으면 id 가 앞선 사진
		insertMenuImage(second, 1, "menu/second-a.jpg", null, "DONE");
		insertMenuImage(second, 1, "menu/second-b.jpg", "menu/second-b_320.jpg", "DONE");
		// 처리 중·실패 사진뿐인 메뉴는 썸네일이 없다
		insertMenuImage(third, 1, "menu/third-pending.jpg", null, "PENDING");
		insertMenuImage(third, 2, "menu/third-failed.jpg", null, "FAILED");
		insertMenuImage(fifth, 3, "menu/fifth.jpg", "menu/fifth_320.jpg", "DONE");
		insertMenuImage(deletedTop, 1, "menu/deleted.jpg", "menu/deleted_320.jpg", "DONE");
		// 가게 사진 여러 장 + 맨 앞 순서는 처리 중
		insertStoreImage(MANY_MENUS_STORE, 0, "store/101-pending.jpg", "store/101-pending_320.jpg", "PENDING");
		insertStoreImage(MANY_MENUS_STORE, 2, "store/101-2.jpg", "store/101-2_320.jpg", "DONE");
		insertStoreImage(MANY_MENUS_STORE, 1, "store/101-1.jpg", null, "DONE");

		// 메뉴 5개 이하 가게
		long only = insertMenu(FEW_MENUS_STORE, "단팥빵", 3, false);
		insertMenu(FEW_MENUS_STORE, "삭제된 메뉴", 100, true);
		insertMenuImage(only, 1, "menu/only.jpg", "menu/only_320.jpg", "DONE");
		insertStoreImage(FEW_MENUS_STORE, 1, "store/102-a.jpg", "store/102-a_320.jpg", "DONE");
		insertStoreImage(FEW_MENUS_STORE, 1, "store/102-b.jpg", "store/102-b_320.jpg", "DONE");

		// 메뉴가 삭제된 것뿐이고 사진은 처리 중뿐인 가게
		insertMenu(DELETED_ONLY_STORE, "삭제된 메뉴", 7, true);
		insertStoreImage(DELETED_ONLY_STORE, 1, "store/104-pending.jpg", null, "PENDING");

		// 엔진 순서(가게 ID 순이 아니다)를 지켜야 한다. EMPTY_STORE 는 메뉴도 사진도 없다.
		List<Long> storeIds = List.of(FEW_MENUS_STORE, EMPTY_STORE, MANY_MENUS_STORE, DELETED_ONLY_STORE);
		List<StoreSearchHit> hits = hits(storeIds);
		givenStores(storeIds);

		List<StoreSearchResponse> assembled = assembler.assemble(hits);

		assertThat(assembled).isEqualTo(assembleWithLegacyQueries(hits));
		// 썸네일 조회에는 응답에 실리는 메뉴(가게당 상위 5개)의 ID 만 넘어간다 — 6위 밖 메뉴·삭제된 메뉴는 없다
		assertThat(thumbnailQueryMenuIds()).containsExactlyInAnyOrder(first, second, third, fourth, fifth, only);
		// 같다는 것만으로는 둘 다 비어 있어도 통과한다 — 기대하는 내용도 직접 확인한다
		StoreSearchResponse manyMenus = assembled.get(2);
		assertThat(manyMenus.id()).isEqualTo(MANY_MENUS_STORE);
		assertThat(manyMenus.thumbnailImageKey()).isEqualTo("store/101-1.jpg");
		assertThat(manyMenus.topMenus()).extracting(MenuSummaryDto::menuId)
			.containsExactly(first, second, third, fourth, fifth);
		assertThat(manyMenus.topMenus()).extracting(MenuSummaryDto::thumbnailImageKey)
			.containsExactly("menu/first-1_320.jpg", "menu/second-a.jpg", null, null, "menu/fifth_320.jpg");
		assertThat(assembled.get(0).thumbnailImageKey()).isEqualTo("store/102-a_320.jpg");
		assertThat(assembled.get(0).topMenus()).extracting(MenuSummaryDto::menuId).containsExactly(only);
		assertThat(assembled.get(1).thumbnailImageKey()).isNull();
		assertThat(assembled.get(1).topMenus()).isEmpty();
		assertThat(assembled.get(3).thumbnailImageKey()).isNull();
		assertThat(assembled.get(3).topMenus()).isEmpty();
	}

	@Test
	@DisplayName("좋아요가 같은 메뉴는 기존 쿼리가 준 순서 그대로 앞의 5개 — 고르는 기준을 바꾸지 않는다")
	void assemble_keepsLegacyQueryOrderForTiedLikeCounts() {
		for (int i = 0; i < TOP_MENU_LIMIT + 3; i++) {
			insertMenu(TIED_LIKES_STORE, "메뉴" + i, 0, false);
		}
		List<Long> storeIds = List.of(TIED_LIKES_STORE);
		List<StoreSearchHit> hits = hits(storeIds);
		givenStores(storeIds);
		List<Long> legacyOrder = menuRepository.findTopRecommendedMenusByStoreIds(storeIds).stream()
			.map(StoreMenuProjection::getMenuId)
			.toList();

		List<StoreSearchResponse> assembled = assembler.assemble(hits);

		assertThat(assembled).isEqualTo(assembleWithLegacyQueries(hits));
		assertThat(assembled.get(0).topMenus()).extracting(MenuSummaryDto::menuId)
			.containsExactlyElementsOf(legacyOrder.subList(0, TOP_MENU_LIMIT));
		assertThat(thumbnailQueryMenuIds())
			.containsExactlyInAnyOrderElementsOf(legacyOrder.subList(0, TOP_MENU_LIMIT));
	}

	@Test
	@DisplayName("매칭된 메뉴는 좋아요 순위 밖이어도 앞으로 올라오고 그 메뉴의 썸네일도 붙는다 — 이전 구현과 같은 응답")
	void assemble_matchedMenuOutsideTop_matchesLegacyResult() {
		List<Long> menuIds = new ArrayList<>();
		for (int i = 0; i < TOP_MENU_LIMIT + 2; i++) {
			menuIds.add(insertMenu(MANY_MENUS_STORE, "메뉴" + i, 100 - i, false));
		}
		long matched = menuIds.get(TOP_MENU_LIMIT + 1);
		insertMenuImage(matched, 1, "menu/matched.jpg", "menu/matched_320.jpg", "DONE");
		insertMenuImage(menuIds.get(0), 1, "menu/top.jpg", null, "DONE");
		// 순위 밖으로 밀려난 5위 메뉴의 사진은 읽을 필요가 없다
		insertMenuImage(menuIds.get(TOP_MENU_LIMIT - 1), 1, "menu/pushed-out.jpg", null, "DONE");
		List<StoreSearchHit> hits = List.of(
			new StoreSearchHit(MANY_MENUS_STORE, null, null, List.of(matched), List.of()));
		givenStores(List.of(MANY_MENUS_STORE));

		List<StoreSearchResponse> assembled = assembler.assemble(hits);

		assertThat(assembled).isEqualTo(assembleWithLegacyQueries(hits));
		List<Long> expected = List.of(matched, menuIds.get(0), menuIds.get(1), menuIds.get(2), menuIds.get(3));
		assertThat(assembled.get(0).topMenus()).extracting(MenuSummaryDto::menuId).containsExactlyElementsOf(expected);
		assertThat(assembled.get(0).topMenus()).extracting(MenuSummaryDto::matched)
			.containsExactly(true, false, false, false, false);
		assertThat(assembled.get(0).topMenus()).extracting(MenuSummaryDto::thumbnailImageKey)
			.containsExactly("menu/matched_320.jpg", "menu/top.jpg", null, null, null);
		assertThat(thumbnailQueryMenuIds()).containsExactlyInAnyOrderElementsOf(expected);
	}

	@Test
	@DisplayName("사진은 가게·메뉴마다 첫 장만 읽는다")
	void firstThumbnailQueries_readOneRowPerOwner() {
		long menuWithPhotos = insertMenu(MANY_MENUS_STORE, "소금빵", 9, false);
		for (int order = 1; order <= 4; order++) {
			insertMenuImage(menuWithPhotos, order, "menu/" + order + ".jpg", null, "DONE");
			insertStoreImage(MANY_MENUS_STORE, order, "store/" + order + ".jpg", null, "DONE");
		}

		List<MenuImageThumbnailProjection> menuThumbnails = menuImageFileRepository
			.findFirstThumbnailsByMenuIdIn(List.of(menuWithPhotos));
		List<StoreImageThumbnailProjection> storeThumbnails = storeImageFileRepository
			.findFirstThumbnailsByStoreIdIn(List.of(MANY_MENUS_STORE, FEW_MENUS_STORE));

		assertThat(menuThumbnails).hasSize(1);
		assertThat(menuThumbnails.get(0).getAwsKey()).isEqualTo("menu/1.jpg");
		assertThat(storeThumbnails).hasSize(1);
		assertThat(storeThumbnails.get(0).getAwsKey()).isEqualTo("store/1.jpg");
	}

	@SuppressWarnings("unchecked")
	private List<Long> thumbnailQueryMenuIds() {
		ArgumentCaptor<List<Long>> menuIds = ArgumentCaptor.forClass(List.class);
		verify(recordingMenuImageFileRepository).findFirstThumbnailsByMenuIdIn(menuIds.capture());
		return menuIds.getValue();
	}

	/** 바꾸기 전 조립 로직 그대로 — 가게의 메뉴·사진을 전부 읽고 자바에서 첫 장·상위 N개를 고른다. */
	private List<StoreSearchResponse> assembleWithLegacyQueries(List<StoreSearchHit> hits) {
		List<Long> orderedStoreIds = hits.stream().map(StoreSearchHit::storeId).distinct().toList();
		Map<Long, StoreSearchHit> hitByStoreId = hits.stream()
			.collect(Collectors.toMap(StoreSearchHit::storeId, hit -> hit, (first, second) -> first));
		Map<Long, String> storeThumbnailMap = storeImageFileRepository.findThumbnailsByStoreIdIn(orderedStoreIds)
			.stream()
			.collect(Collectors.toMap(
				StoreImageThumbnailProjection::getStoreId,
				p -> p.getResizedKey320() != null ? p.getResizedKey320() : p.getAwsKey(),
				(first, second) -> first));
		Map<Long, List<StoreMenuProjection>> menusByStore = menuRepository
			.findTopRecommendedMenusByStoreIds(orderedStoreIds).stream()
			.collect(Collectors.groupingBy(StoreMenuProjection::getStoreId));
		List<Long> menuIds = menusByStore.values().stream()
			.flatMap(List::stream)
			.map(StoreMenuProjection::getMenuId)
			.toList();
		Map<Long, String> menuThumbnailMap = menuIds.isEmpty()
			? Collections.emptyMap()
			: menuImageFileRepository.findThumbnailsByMenuIdIn(menuIds).stream()
				.collect(Collectors.toMap(
					MenuImageThumbnailProjection::getMenuId,
					p -> p.getResizedKey320() != null ? p.getResizedKey320() : p.getAwsKey(),
					(first, second) -> first));
		Map<Long, Store> storeById = storeRepository.findAllById(orderedStoreIds).stream()
			.collect(Collectors.toMap(Store::getId, store -> store));
		return orderedStoreIds.stream()
			.map(storeById::get)
			.map(store -> StoreSearchResponse.ofDb(
				store,
				storeThumbnailMap.get(store.getId()),
				legacyOrderMenus(menusByStore.getOrDefault(store.getId(), List.of()),
					hitByStoreId.get(store.getId()).matchedMenuIds(), menuThumbnailMap),
				List.of()))
			.toList();
	}

	/** 바꾸기 전 orderMenus 그대로 — 매칭 메뉴 우선의 안정 정렬 → 상위 N개 → DTO. */
	private List<MenuSummaryDto> legacyOrderMenus(List<StoreMenuProjection> menus, List<Long> matchedMenuIds,
			Map<Long, String> menuThumbnailMap) {
		Map<Long, Integer> matchedOrder = new HashMap<>();
		for (int i = 0; i < matchedMenuIds.size(); i++) {
			matchedOrder.putIfAbsent(matchedMenuIds.get(i), i);
		}
		return menus.stream()
			.sorted(Comparator.comparingInt(menu -> matchedOrder.getOrDefault(menu.getMenuId(), Integer.MAX_VALUE)))
			.limit(TOP_MENU_LIMIT)
			.map(menu -> MenuSummaryDto.of(menu, menuThumbnailMap.get(menu.getMenuId()),
				matchedOrder.containsKey(menu.getMenuId())))
			.toList();
	}

	private List<StoreSearchHit> hits(List<Long> storeIds) {
		return storeIds.stream()
			.map(storeId -> new StoreSearchHit(storeId, null, null, List.of(), List.of()))
			.toList();
	}

	private void givenStores(List<Long> storeIds) {
		List<Store> stores = storeIds.stream().map(this::store).toList();
		given(storeRepository.findAllById(anyList())).willReturn(stores);
	}

	private Store store(Long id) {
		Store store = mock(Store.class);
		given(store.getId()).willReturn(id);
		given(store.getName()).willReturn("가게" + id);
		given(store.getAddress()).willReturn("서울 성동구 성수동");
		return store;
	}

	private long insertMenu(long storeId, String name, int likeCount, boolean deleted) {
		jdbcTemplate.update("""
				INSERT INTO menu (store_id, name, normalized_name, like_count, deleted_at, created_at)
				VALUES (?, ?, ?, ?, CASE WHEN ? THEN CURRENT_TIMESTAMP END, CURRENT_TIMESTAMP)
				""", storeId, name, name, likeCount, deleted);
		return jdbcTemplate.queryForObject("SELECT MAX(id) FROM menu", Long.class);
	}

	private void insertMenuImage(long menuId, int displayOrder, String awsKey, String resizedKey320, String status) {
		jdbcTemplate.update("""
				INSERT INTO menu_image_file (menu_id, display_order, name, mime_type, aws_key, resized_key_320,
					processing_status, created_at)
				VALUES (?, ?, ?, 'IMAGE_JPEG', ?, ?, ?, CURRENT_TIMESTAMP)
				""", menuId, displayOrder, awsKey, awsKey, resizedKey320, status);
	}

	private void insertStoreImage(long storeId, int displayOrder, String awsKey, String resizedKey320,
			String status) {
		jdbcTemplate.update("""
				INSERT INTO store_image_file (store_id, display_order, name, mime_type, aws_key, resized_key_320,
					processing_status, created_at)
				VALUES (?, ?, ?, 'IMAGE_JPEG', ?, ?, ?, CURRENT_TIMESTAMP)
				""", storeId, displayOrder, awsKey, awsKey, resizedKey320, status);
	}
}

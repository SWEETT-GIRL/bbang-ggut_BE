package sweetgirl.croissant.breadranking.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.enums.TextureChip;
import sweetgirl.croissant.breadranking.dto.response.BreadBoardEntryResponse;
import sweetgirl.croissant.breadranking.dto.response.BreadBoardResponse;
import sweetgirl.croissant.breadranking.dto.response.BreadCategoryResponse;
import sweetgirl.croissant.breadranking.dto.response.BreadRatingChipsResponse.Chip;
import sweetgirl.croissant.breadranking.dto.response.StoreSummaryResponse;
import sweetgirl.croissant.breadranking.entity.BreadCategory;
import sweetgirl.croissant.breadranking.repository.BreadBoardJdbcRepository;
import sweetgirl.croissant.breadranking.repository.BreadBoardJdbcRepository.BoardRow;
import sweetgirl.croissant.breadranking.repository.BreadBoardJdbcRepository.CategoryVotersRow;
import sweetgirl.croissant.breadranking.repository.BreadCategoryRepository;
import sweetgirl.croissant.ranking.service.RankChangeResolver;
import sweetgirl.croissant.ranking.service.RankChanges;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.repository.StoreRepository;
import sweetgirl.croissant.store.service.StoreThumbnailReader;

/**
 * 빵집 랭킹(랭킹 탭 "빵집") — 빵 종류 하나 안에서 가게를 "1등으로 매긴 사람 수"로 줄 세운다. 전국만, 게스트도 본다.
 * 집계는 {@link BreadBoardJdbcRepository} 가 쿼리 한 번에 하고, 여기서는 가게 정보·썸네일·어제 대비 변동을 붙인다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BreadBoardQueryService {

	/** 탭에 생기는 기준 — 서로 다른 회원 N명이 1등을 매김 */
	public static final int MIN_VOTERS_FOR_TAB = 10;
	/** 순위가 열리는 최소 표 수(카테고리 전체) */
	public static final int MIN_VOTES = 3;
	public static final int DEFAULT_LIMIT = 50;
	public static final int MAX_LIMIT = 100;
	static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	private final BreadBoardJdbcRepository boardRepository;
	private final BreadCategoryRepository categoryRepository;
	private final BreadCategoryQueryService categoryQueryService;
	private final StoreRepository storeRepository;
	private final StoreThumbnailReader thumbnailReader;
	private final RankChangeResolver rankChangeResolver;

	/** 탭에 뜨는 카테고리 — 10명 이상, 사람 수 많은 순(앞 4개는 탭, 나머지는 "전체" 격자). */
	public List<BreadCategoryResponse> getBoardCategories() {
		List<CategoryVotersRow> rows = boardRepository.findCategoriesByVoters(MIN_VOTERS_FOR_TAB);
		Map<Long, BreadCategory> categories = categoriesOf(rows.stream().map(CategoryVotersRow::categoryId).toList());
		return rows.stream()
			.filter(row -> categories.containsKey(row.categoryId()))
			.map(row -> BreadCategoryResponse.of(categories.get(row.categoryId()), row.voters()))
			.toList();
	}

	public BreadBoardResponse getBoard(Long categoryId, Integer limit) {
		BreadCategory category = categoryQueryService.getCategory(categoryId);
		int lim = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
		List<BoardRow> rows = boardRepository.findBoard(categoryId, kindName(category), lim);
		long totalVotes = rows.isEmpty() ? 0 : rows.get(0).totalVotes();
		BreadCategoryResponse categoryResponse = BreadCategoryResponse.of(category,
			boardRepository.countVoters(categoryId));
		if (totalVotes < MIN_VOTES) {
			return new BreadBoardResponse(categoryResponse, totalVotes, MIN_VOTES, (int)(MIN_VOTES - totalVotes),
				List.of());
		}
		List<Long> storeIds = rows.stream().map(BoardRow::storeId).toList();
		Map<Long, Store> stores = storeRepository.findAllById(storeIds).stream()
			.collect(Collectors.toMap(Store::getId, Function.identity()));
		Map<Long, String> thumbnails = thumbnailReader.thumbnailsOf(storeIds);
		RankChanges changes = rankChangeResolver.resolve(
			RankChangeResolver.BREAD_BOARD, categoryId, RankChangeResolver.NATIONWIDE, LocalDate.now(ZONE));
		List<BreadBoardEntryResponse> entries = new ArrayList<>();
		for (BoardRow row : rows) {
			Store store = stores.get(row.storeId());
			if (store == null) {
				continue;
			}
			int rank = entries.size() + 1;
			entries.add(new BreadBoardEntryResponse(
				rank,
				StoreSummaryResponse.of(store, thumbnails.get(store.getId())),
				row.votes(),
				againRate(row.againYes(), row.againTotal()),
				textureChip(row.topTexture()),
				changes.changeOf(store.getId(), rank),
				changes.isNew(store.getId())));
		}
		return new BreadBoardResponse(categoryResponse, totalVotes, MIN_VOTES, 0, entries);
	}

	/** 가게 상세용 전국 등수 — 순위가 안 열렸거나 상위 100 밖이면 null. */
	public Integer nationalRankOf(BreadCategory category, long storeId) {
		List<BoardRow> rows = boardRepository.findBoard(category.getId(), kindName(category), MAX_LIMIT);
		if (rows.isEmpty() || rows.get(0).totalVotes() < MIN_VOTES) {
			return null;
		}
		for (int index = 0; index < rows.size(); index++) {
			if (rows.get(index).storeId() == storeId) {
				return index + 1;
			}
		}
		return null;
	}

	Map<Long, BreadCategory> categoriesOf(List<Long> categoryIds) {
		if (categoryIds.isEmpty()) {
			return Map.of();
		}
		return categoryRepository.findAllById(categoryIds).stream()
			.collect(Collectors.toMap(BreadCategory::getId, Function.identity()));
	}

	static String kindName(BreadCategory category) {
		return category.getMenuKind() == null ? "" : category.getMenuKind().name();
	}

	static Double againRate(long againYes, long againTotal) {
		return againTotal == 0 ? null : (double)againYes / againTotal;
	}

	static Chip textureChip(String name) {
		return Arrays.stream(TextureChip.values())
			.filter(chip -> chip.name().equals(name))
			.findFirst()
			.map(Chip::of)
			.orElse(null);
	}
}

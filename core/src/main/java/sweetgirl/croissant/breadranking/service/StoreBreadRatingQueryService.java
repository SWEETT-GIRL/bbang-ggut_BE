package sweetgirl.croissant.breadranking.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadranking.dto.response.BreadCategoryResponse;
import sweetgirl.croissant.breadranking.dto.response.BreadRatingChipsResponse.Chip;
import sweetgirl.croissant.breadranking.dto.response.StoreBreadRatingResponse;
import sweetgirl.croissant.breadranking.dto.response.StoreBreadRatingResponse.TextureStat;
import sweetgirl.croissant.breadranking.entity.BreadCategory;
import sweetgirl.croissant.breadranking.repository.BreadBoardJdbcRepository;
import sweetgirl.croissant.breadranking.repository.BreadBoardJdbcRepository.MenuPriceRow;
import sweetgirl.croissant.breadranking.repository.BreadBoardJdbcRepository.StoreCategoryRow;
import sweetgirl.croissant.breadranking.repository.BreadBoardJdbcRepository.TextureStatRow;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.enums.StoreStatus;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreRepository;

/**
 * 가게 상세(V2)의 빵 종류별 평가 — 평가한 사람 5명 이상인 종류만. 전국 등수는 그 종류의 판을 읽어 찾는다(가게당 종류 수만큼).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreBreadRatingQueryService {

	/** 가게 상세에 평가가 뜨는 최소 인원 */
	public static final int MIN_RATERS = 5;
	static final int TOP_TEXTURES = 3;

	private final BreadBoardJdbcRepository boardRepository;
	private final BreadBoardQueryService boardQueryService;
	private final StoreRepository storeRepository;

	public List<StoreBreadRatingResponse> getStoreRatings(Long storeId) {
		storeRepository.findById(storeId)
			.filter(store -> store.getStatus() == StoreStatus.ACTIVE)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		List<StoreCategoryRow> stats = boardRepository.findStoreCategoryStats(storeId, MIN_RATERS);
		if (stats.isEmpty()) {
			return List.of();
		}
		List<Long> categoryIds = stats.stream().map(StoreCategoryRow::categoryId).toList();
		Map<Long, BreadCategory> categories = boardQueryService.categoriesOf(categoryIds);
		Map<Long, List<TextureStatRow>> texturesByCategory = boardRepository.findStoreTextureStats(storeId, categoryIds)
			.stream()
			.collect(Collectors.groupingBy(TextureStatRow::categoryId));
		Map<Long, MenuPriceRow> priceByCategory = boardRepository.findStoreMenuPrices(storeId, categoryIds).stream()
			.collect(Collectors.toMap(MenuPriceRow::categoryId, Function.identity(), (first, second) -> first));
		List<StoreBreadRatingResponse> responses = new ArrayList<>();
		for (StoreCategoryRow stat : stats) {
			BreadCategory category = categories.get(stat.categoryId());
			if (category == null) {
				continue;
			}
			MenuPriceRow price = priceByCategory.get(stat.categoryId());
			responses.add(new StoreBreadRatingResponse(
				BreadCategoryResponse.of(category, 0),
				boardQueryService.nationalRankOf(category, storeId),
				stat.raters(),
				BreadBoardQueryService.againRate(stat.againYes(), stat.againTotal()),
				textureStats(texturesByCategory.getOrDefault(stat.categoryId(), List.of())),
				price == null ? null : price.menuId(),
				price == null ? null : price.price()));
		}
		return responses;
	}

	/** 행은 수 많은 순·이름순으로 온다 — 앞 3개, 비율 = 그 칩을 고른 태그 / 식감을 하나라도 고른 태그. */
	static List<TextureStat> textureStats(List<TextureStatRow> rows) {
		List<TextureStat> stats = new ArrayList<>();
		for (TextureStatRow row : rows) {
			Chip chip = BreadBoardQueryService.textureChip(row.chip());
			if (chip == null || row.rated() == 0) {
				continue;
			}
			stats.add(new TextureStat(chip.code(), chip.label(), (double)row.count() / row.rated()));
			if (stats.size() == TOP_TEXTURES) {
				break;
			}
		}
		return stats;
	}
}

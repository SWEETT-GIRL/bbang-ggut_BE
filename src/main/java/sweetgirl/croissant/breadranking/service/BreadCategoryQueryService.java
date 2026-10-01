package sweetgirl.croissant.breadranking.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadranking.code.BreadRankingErrorCode;
import sweetgirl.croissant.breadranking.dto.response.BreadCategoryResponse;
import sweetgirl.croissant.breadranking.entity.BreadCategory;
import sweetgirl.croissant.breadranking.exception.BreadRankingException;
import sweetgirl.croissant.breadranking.repository.BreadCategoryRepository;

/** 공용 빵 종류 목록 — 기본 89종 + 사용자가 만든 것. 게스트도 본다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BreadCategoryQueryService {

	private final BreadCategoryRepository categoryRepository;

	/** 1등 매긴 사람 수 순 — "빵 추가" 시트가 이 순서로 보여 준다. */
	public List<BreadCategoryResponse> getCategories() {
		return categoryRepository.findAllOrderByVoters().stream()
			.map(row -> BreadCategoryResponse.of(row.category(), row.voters()))
			.toList();
	}

	public BreadCategory getCategory(Long categoryId) {
		return categoryRepository.findById(categoryId)
			.orElseThrow(() -> new BreadRankingException(BreadRankingErrorCode.BREAD_CATEGORY_NOT_FOUND));
	}
}

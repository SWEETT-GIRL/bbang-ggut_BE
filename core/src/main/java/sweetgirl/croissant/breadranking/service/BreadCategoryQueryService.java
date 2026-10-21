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

/** 공용 빵 종류 목록 — 기본 89종 + 사용자가 만든 것 중 남에게 보여도 되는 것. 게스트도 본다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BreadCategoryQueryService {

	/**
	 * 사용자 생성 종류가 남에게 보이는 데 필요한, 그 종류를 자기 탭에 넣은 서로 다른 회원 수(#843). 만든 사람 혼자 쓰는 이름(자판 실수·
	 * 욕설·오타)은 본인에게만 보인다. 빵집 판 탭(10명)보다 낮은 이유는 "쓰는 사람이 있다"만 보면 되기 때문이다.
	 */
	public static final int MIN_TAB_MEMBERS_FOR_SHARED = 3;

	private final BreadCategoryRepository categoryRepository;

	/**
	 * 기본 종류 + 내가 만든 것 + {@link #MIN_TAB_MEMBERS_FOR_SHARED} 명 이상이 쓰는 것. 고정 자리(기본 8종) → 1등 매긴 사람 수 순 —
	 * "빵 추가" 시트가 이 순서로 보여 준다.
	 *
	 * @param memberId 로그인 회원 id, 게스트·비로그인은 null
	 */
	public List<BreadCategoryResponse> getCategories(Long memberId) {
		return categoryRepository.findVisibleOrderByDisplay(memberId, MIN_TAB_MEMBERS_FOR_SHARED).stream()
			.map(row -> BreadCategoryResponse.of(row.category(), row.voters()))
			.toList();
	}

	public BreadCategory getCategory(Long categoryId) {
		return categoryRepository.findById(categoryId)
			.orElseThrow(() -> new BreadRankingException(BreadRankingErrorCode.BREAD_CATEGORY_NOT_FOUND));
	}
}

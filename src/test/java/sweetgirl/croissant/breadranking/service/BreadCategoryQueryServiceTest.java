package sweetgirl.croissant.breadranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.breadranking.code.BreadRankingErrorCode;
import sweetgirl.croissant.breadranking.dto.projection.BreadCategoryVoters;
import sweetgirl.croissant.breadranking.dto.response.BreadCategoryResponse;
import sweetgirl.croissant.breadranking.entity.BreadCategory;
import sweetgirl.croissant.breadranking.enums.BreadGroup;
import sweetgirl.croissant.breadranking.exception.BreadRankingException;
import sweetgirl.croissant.breadranking.repository.BreadCategoryRepository;

@ExtendWith(MockitoExtension.class)
class BreadCategoryQueryServiceTest {

	@Mock
	private BreadCategoryRepository categoryRepository;

	@InjectMocks
	private BreadCategoryQueryService service;

	@Test
	@DisplayName("공용 목록은 리포지토리 순서 그대로 — 내 id 와 3명 기준을 넘기고, 사람 수를 같이 준다")
	void getCategories() {
		BreadCategory custom = BreadCategory.custom("트러플 소금빵", BreadGroup.빵, 3L);
		ReflectionTestUtils.setField(custom, "id", 200L);
		given(categoryRepository.findVisibleOrderByDisplay(3L, BreadCategoryQueryService.MIN_TAB_MEMBERS_FOR_SHARED))
			.willReturn(List.of(new BreadCategoryVoters(custom, 12)));

		List<BreadCategoryResponse> categories = service.getCategories(3L);

		assertThat(categories).hasSize(1);
		assertThat(categories.get(0).voters()).isEqualTo(12);
		assertThat(categories.get(0).menuKind()).isNull();
		assertThat(categories.get(0).breadGroup()).isEqualTo("빵");
	}

	@Test
	@DisplayName("게스트·비로그인은 memberId 없이 묻는다 — 내 것 없이 기본 + 3명 이상만")
	void getCategories_guestPassesNullMember() {
		given(categoryRepository.findVisibleOrderByDisplay(null, BreadCategoryQueryService.MIN_TAB_MEMBERS_FOR_SHARED))
			.willReturn(List.of());

		assertThat(service.getCategories(null)).isEmpty();
	}

	@Test
	@DisplayName("없는 카테고리는 404")
	void getCategoryNotFound() {
		given(categoryRepository.findById(999L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.getCategory(999L))
			.isInstanceOf(BreadRankingException.class)
			.extracting("errorCode")
			.isEqualTo(BreadRankingErrorCode.BREAD_CATEGORY_NOT_FOUND);
	}
}

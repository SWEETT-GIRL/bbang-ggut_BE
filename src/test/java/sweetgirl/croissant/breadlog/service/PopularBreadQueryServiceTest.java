package sweetgirl.croissant.breadlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import sweetgirl.croissant.breadlog.dto.projection.BreadCategoryCountProjection;
import sweetgirl.croissant.breadlog.dto.response.PopularBreadResponse;
import sweetgirl.croissant.breadlog.entity.BreadRecordTag;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;

@ExtendWith(MockitoExtension.class)
class PopularBreadQueryServiceTest {

	@Mock
	private BreadRecordTagRepository tagRepository;

	@InjectMocks
	private PopularBreadQueryService popularBreadQueryService;

	private BreadCategoryCountProjection row(String name, long count) {
		BreadCategoryCountProjection row = mock(BreadCategoryCountProjection.class);
		given(row.getName()).willReturn(name);
		given(row.getTagCount()).willReturn(count);
		return row;
	}

	@Test
	@DisplayName("최근 30 일 태그를 분류별로 센 순서를 그대로 싣고, '빵' · '기타'는 묻지 않는다")
	void mapsRowsInOrder() {
		List<BreadCategoryCountProjection> rows = List.of(row("소금빵", 120L), row("크루아상", 80L), row("단팥빵", 33L));
		given(tagRepository.findPopularCategories(any(LocalDateTime.class), eq(BreadRecordTag.fallbackCategories()),
			eq(PageRequest.of(0, 3))))
			.willReturn(rows);

		assertThat(popularBreadQueryService.getPopularBreads(3)).containsExactly(
			new PopularBreadResponse("소금빵", 120L),
			new PopularBreadResponse("크루아상", 80L),
			new PopularBreadResponse("단팥빵", 33L));

		then(tagRepository).should().findPopularCategories(
			argThat(since -> since.isAfter(LocalDateTime.now().minusDays(31))
				&& since.isBefore(LocalDateTime.now().minusDays(29))),
			any(), any(Pageable.class));
	}

	@Test
	@DisplayName("size 는 1~20 으로 자른다 — 0 이하는 1, 20 넘으면 20")
	void clampsSize() {
		given(tagRepository.findPopularCategories(any(), any(), any(Pageable.class))).willReturn(List.of());

		popularBreadQueryService.getPopularBreads(0);
		popularBreadQueryService.getPopularBreads(99);

		then(tagRepository).should().findPopularCategories(any(), any(), eq(PageRequest.of(0, 1)));
		then(tagRepository).should().findPopularCategories(any(), any(), eq(PageRequest.of(0, 20)));
	}
}

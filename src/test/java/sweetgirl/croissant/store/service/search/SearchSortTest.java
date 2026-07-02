package sweetgirl.croissant.store.service.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SearchSortTest {

	@Test
	@DisplayName("null·빈 값은 기본 정렬 RELEVANCE로 매핑한다")
	void from_defaultsToRelevance() {
		assertThat(SearchSort.from(null)).isEqualTo(SearchSort.RELEVANCE);
		assertThat(SearchSort.from("")).isEqualTo(SearchSort.RELEVANCE);
		assertThat(SearchSort.from("   ")).isEqualTo(SearchSort.RELEVANCE);
	}

	@Test
	@DisplayName("정렬 문자열을 대소문자 무관하게 매핑한다")
	void from_mapsValuesCaseInsensitively() {
		assertThat(SearchSort.from("relevance")).isEqualTo(SearchSort.RELEVANCE);
		assertThat(SearchSort.from("distance")).isEqualTo(SearchSort.DISTANCE);
		assertThat(SearchSort.from("POPULARITY")).isEqualTo(SearchSort.POPULARITY);
	}

	@Test
	@DisplayName("알 수 없는 정렬 값은 예외를 던진다")
	void from_throwsOnUnknownValue() {
		assertThatThrownBy(() -> SearchSort.from("nearest"))
			.isInstanceOf(IllegalArgumentException.class);
	}
}

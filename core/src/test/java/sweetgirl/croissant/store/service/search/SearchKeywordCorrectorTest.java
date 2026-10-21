package sweetgirl.croissant.store.service.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.store.entity.SearchKeywordCorrection;
import sweetgirl.croissant.store.repository.SearchKeywordCorrectionRepository;

@ExtendWith(MockitoExtension.class)
class SearchKeywordCorrectorTest {

	@Mock
	private SearchKeywordCorrectionRepository repository;

	private SearchKeywordCorrector corrector;

	@BeforeEach
	void setUp() {
		corrector = new SearchKeywordCorrector(repository);
	}

	private static SearchKeywordCorrection correction(String keyword, String corrected) {
		return SearchKeywordCorrection.fromSearchMiss(SearchTextNormalizer.normalize(keyword), keyword, corrected, 1L);
	}

	@Test
	@DisplayName("검색어 전체가 교정 목록에 있으면 통째로 바꾼다 — 띄어쓰기가 달라도 같은 키다")
	void correct_whole() {
		given(repository.findAll()).willReturn(List.of(correction("이웃집 퉁퉁이", "이웃집통통이")));

		assertThat(corrector.correct("이웃집퉁퉁이")).isEqualTo("이웃집통통이");
	}

	@Test
	@DisplayName("전체가 없으면 단어마다 바꾸고 나머지 단어는 그대로 둔다")
	void correct_tokens() {
		given(repository.findAll()).willReturn(List.of(correction("콜버", "콜드버터베이크샵")));

		assertThat(corrector.correct("콜버 탄방")).isEqualTo("콜드버터베이크샵 탄방");
	}

	@Test
	@DisplayName("한 번만 바꾼다 — 서로를 가리키는 교정이 있어도 돌지 않는다")
	void correct_appliesOnce() {
		given(repository.findAll()).willReturn(List.of(correction("가나", "다라"), correction("다라", "가나")));

		assertThat(corrector.correct("가나")).isEqualTo("다라");
	}

	@Test
	@DisplayName("교정할 것이 없으면 원문을 그대로 돌려준다")
	void correct_noMatch() {
		given(repository.findAll()).willReturn(List.of(correction("콜버", "콜드버터베이크샵")));

		assertThat(corrector.correct("성심당 본점")).isEqualTo("성심당 본점");
		assertThat(corrector.correct(" ")).isEqualTo(" ");
	}

	@Test
	@DisplayName("자동완성은 단어 단위로 바꾸지 않는다 — 치는 도중의 말이 엉뚱하게 바뀌면 안 된다")
	void correctWhole_onlyWholeMatch() {
		given(repository.findAll()).willReturn(List.of(correction("콜버", "콜드버터베이크샵")));

		assertThat(corrector.correctWhole("콜버")).isEqualTo("콜드버터베이크샵");
		assertThat(corrector.correctWhole("콜버 탄")).isEqualTo("콜버 탄");
	}

	@Test
	@DisplayName("목록은 한 번 읽어 두고, invalidate 하면 다음 접근에서 다시 읽는다")
	void cachesUntilInvalidated() {
		given(repository.findAll()).willReturn(List.of(), List.of(correction("뚜쥬르", "뚜쥬루")));

		assertThat(corrector.correct("뚜쥬르")).isEqualTo("뚜쥬르");
		assertThat(corrector.correct("뚜쥬르")).isEqualTo("뚜쥬르");
		corrector.invalidate();

		assertThat(corrector.correct("뚜쥬르")).isEqualTo("뚜쥬루");
		verify(repository, times(2)).findAll();
	}

	@Test
	@DisplayName("다시 읽다 실패하면 직전 목록을 그대로 쓴다 — 교정 때문에 검색이 실패하면 안 된다")
	void keepsPreviousOnFailure() {
		given(repository.findAll()).willReturn(List.of(correction("뚜쥬르", "뚜쥬루")))
			.willThrow(new IllegalStateException("DB 끊김"));
		corrector.correct("뚜쥬르");
		corrector.invalidate();

		assertThat(corrector.correct("뚜쥬르")).isEqualTo("뚜쥬루");
	}
}

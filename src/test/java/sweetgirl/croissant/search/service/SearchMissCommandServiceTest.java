package sweetgirl.croissant.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.search.entity.SearchMiss;
import sweetgirl.croissant.search.enums.SearchMissStatus;
import sweetgirl.croissant.search.repository.SearchMissRepository;
import sweetgirl.croissant.store.service.search.SearchOutcome;

@ExtendWith(MockitoExtension.class)
class SearchMissCommandServiceTest {

	private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 8, 11, 15, 0);

	@Mock
	private SearchMissRepository searchMissRepository;

	@InjectMocks
	private SearchMissCommandService searchMissCommandService;

	@Test
	@DisplayName("처음 보는 검색어는 새로 저장한다")
	void record_savesWhenAbsent() {
		given(searchMissRepository.incrementOccurrence(anyString(), anyString(), any(), any())).willReturn(0);

		searchMissCommandService.record("성수동 크로플", SearchOutcome.EMPTY, OCCURRED_AT);

		ArgumentCaptor<SearchMiss> captor = ArgumentCaptor.forClass(SearchMiss.class);
		verify(searchMissRepository).save(captor.capture());
		SearchMiss saved = captor.getValue();
		assertThat(saved.getKeyword()).isEqualTo("성수동 크로플");
		assertThat(saved.getNormalizedKeyword()).isEqualTo("성수동크로플");
		assertThat(saved.getOutcome()).isEqualTo(SearchOutcome.EMPTY);
		assertThat(saved.getStatus()).isEqualTo(SearchMissStatus.PENDING);
	}

	@Test
	@DisplayName("이미 있는 검색어는 저장하지 않고 횟수만 올린다")
	void record_incrementsWhenPresent() {
		given(searchMissRepository.incrementOccurrence(anyString(), anyString(), any(), any())).willReturn(1);

		searchMissCommandService.record("성수동 크로플", SearchOutcome.EMPTY, OCCURRED_AT);

		verify(searchMissRepository, never()).save(any());
	}

	@Test
	@DisplayName("띄어쓰기가 달라도 같은 정규화 키로 묶인다")
	void record_normalizesSpacingAndCase() {
		given(searchMissRepository.incrementOccurrence(anyString(), anyString(), any(), any())).willReturn(1);

		searchMissCommandService.record("성수동 크로플!!", SearchOutcome.EMPTY, OCCURRED_AT);

		verify(searchMissRepository).incrementOccurrence(
			eq("성수동크로플"), eq("성수동 크로플!!"), eq(SearchOutcome.EMPTY), eq(OCCURRED_AT));
	}

	@Test
	@DisplayName("HIT은 적재하지 않는다")
	void record_ignoresHit() {
		searchMissCommandService.record("성수동 크로플", SearchOutcome.HIT, OCCURRED_AT);

		verifyNoInteractions(searchMissRepository);
	}

	@Test
	@DisplayName("특수문자만 입력한 검색어는 정규화하면 비어 적재하지 않는다")
	void record_ignoresKeywordWithoutMeaningfulCharacters() {
		searchMissCommandService.record("!!! ???", SearchOutcome.EMPTY, OCCURRED_AT);

		verifyNoInteractions(searchMissRepository);
	}

	@Test
	@DisplayName("컬럼 길이를 넘는 검색어는 잘라서 저장한다")
	void record_truncatesOverlongKeyword() {
		given(searchMissRepository.incrementOccurrence(anyString(), anyString(), any(), any())).willReturn(0);
		String overlong = "가".repeat(SearchMiss.MAX_KEYWORD_LENGTH + 50);

		searchMissCommandService.record(overlong, SearchOutcome.EMPTY, OCCURRED_AT);

		ArgumentCaptor<SearchMiss> captor = ArgumentCaptor.forClass(SearchMiss.class);
		verify(searchMissRepository).save(captor.capture());
		// 길이를 넘기면 저장 자체가 실패한다. 봇이 긴 랜덤 문자열을 넣는 경우를 막는다.
		assertThat(captor.getValue().getKeyword()).hasSize(SearchMiss.MAX_KEYWORD_LENGTH);
		assertThat(captor.getValue().getNormalizedKeyword()).hasSize(SearchMiss.MAX_KEYWORD_LENGTH);
	}

	@Test
	@DisplayName("null 검색어는 적재하지 않는다")
	void record_ignoresNullKeyword() {
		searchMissCommandService.record(null, SearchOutcome.EMPTY, OCCURRED_AT);

		verifyNoInteractions(searchMissRepository);
	}
}

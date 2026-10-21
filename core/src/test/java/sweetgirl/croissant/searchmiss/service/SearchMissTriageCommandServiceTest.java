package sweetgirl.croissant.searchmiss.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.searchmiss.entity.SearchMiss;
import sweetgirl.croissant.searchmiss.enums.SearchMissStatus;
import sweetgirl.croissant.searchmiss.enums.SearchMissVerdict;
import sweetgirl.croissant.searchmiss.repository.SearchMissRepository;
import sweetgirl.croissant.store.entity.SearchKeywordCorrection;
import sweetgirl.croissant.store.enums.SearchKeywordCorrectionSource;
import sweetgirl.croissant.store.repository.SearchKeywordCorrectionRepository;
import sweetgirl.croissant.store.service.search.SearchKeywordCorrector;
import sweetgirl.croissant.store.service.search.SearchOutcome;

@ExtendWith(MockitoExtension.class)
class SearchMissTriageCommandServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 18, 6, 0);

	@Mock
	private SearchMissRepository searchMissRepository;

	@Mock
	private SearchKeywordCorrectionRepository correctionRepository;

	@Mock
	private SearchKeywordCorrector searchKeywordCorrector;

	@InjectMocks
	private SearchMissTriageCommandService commandService;

	@Test
	@DisplayName("교정을 자동 출처로 넣고 id 를 돌려준 뒤 교정 캐시를 비운다 — 바로 뒤 재검색이 새 교정을 타야 한다")
	void addCorrection_savesAndInvalidates() {
		given(correctionRepository.existsByNormalizedKeyword("서심당")).willReturn(false);
		given(correctionRepository.save(any(SearchKeywordCorrection.class))).willAnswer(invocation -> {
			SearchKeywordCorrection correction = invocation.getArgument(0);
			ReflectionTestUtils.setField(correction, "id", 10L);
			return correction;
		});

		Optional<Long> id = commandService.addCorrection(" 서심당 ", "성심당", 7L);

		assertThat(id).contains(10L);
		ArgumentCaptor<SearchKeywordCorrection> captor = ArgumentCaptor.forClass(SearchKeywordCorrection.class);
		verify(correctionRepository).save(captor.capture());
		SearchKeywordCorrection saved = captor.getValue();
		assertThat(saved.getNormalizedKeyword()).isEqualTo("서심당");
		assertThat(saved.getKeyword()).isEqualTo("서심당");
		assertThat(saved.getCorrectedQuery()).isEqualTo("성심당");
		assertThat(saved.getSource()).isEqualTo(SearchKeywordCorrectionSource.SEARCH_MISS_AUTO);
		assertThat(saved.getSearchMissId()).isEqualTo(7L);
		verify(searchKeywordCorrector).invalidate();
	}

	@Test
	@DisplayName("같은 검색어 교정이 이미 있으면 넣지 않고 비어 있는 값을 돌려준다")
	void addCorrection_skipsExisting() {
		given(correctionRepository.existsByNormalizedKeyword("서심당")).willReturn(true);

		Optional<Long> id = commandService.addCorrection("서심당", "성심당", 7L);

		assertThat(id).isEmpty();
		verify(correctionRepository, never()).save(any());
		verify(searchKeywordCorrector, never()).invalidate();
	}

	@Test
	@DisplayName("교정 되돌리기는 그 교정을 지우고 캐시를 비운다")
	void removeCorrection() {
		commandService.removeCorrection(10L);

		verify(correctionRepository).deleteById(10L);
		verify(searchKeywordCorrector).invalidate();
	}

	@Test
	@DisplayName("자동 적용이면 판정을 남기고 처리 상태도 바꾼다")
	void recordDecision_apply() {
		SearchMiss searchMiss = SearchMiss.firstOccurrence("서심당", "서심당", SearchOutcome.EMPTY, NOW);
		given(searchMissRepository.findById(1L)).willReturn(Optional.of(searchMiss));
		TriageDecision decision = new TriageDecision(SearchMissVerdict.CORRECTED, SearchMissStatus.ALREADY_EXISTS,
			7L, Map.of("reason", "교정 추가 후 검색됨"));

		commandService.recordDecision(1L, decision, true, NOW);

		assertThat(searchMiss.getAiVerdict()).isEqualTo(SearchMissVerdict.CORRECTED);
		assertThat(searchMiss.getMatchedStoreId()).isEqualTo(7L);
		assertThat(searchMiss.getCountAtTriage()).isEqualTo(1L);
		assertThat(searchMiss.getStatus()).isEqualTo(SearchMissStatus.ALREADY_EXISTS);
		assertThat(searchMiss.getHandledAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("자동 적용 대상이 아니면 판정만 남기고 처리 상태는 그대로 둔다")
	void recordDecision_recordOnly() {
		SearchMiss searchMiss = SearchMiss.firstOccurrence("캎", "캎", SearchOutcome.EMPTY, NOW);
		given(searchMissRepository.findById(1L)).willReturn(Optional.of(searchMiss));
		TriageDecision decision = new TriageDecision(SearchMissVerdict.JUNK, SearchMissStatus.IGNORED, null, Map.of());

		commandService.recordDecision(1L, decision, false, NOW);

		assertThat(searchMiss.getAiVerdict()).isEqualTo(SearchMissVerdict.JUNK);
		assertThat(searchMiss.getStatus()).isEqualTo(SearchMissStatus.PENDING);
	}

	@Test
	@DisplayName("사람이 봐야 하는 판정은 적용 대상이어도 PENDING 으로 둔다")
	void recordDecision_needsReview() {
		SearchMiss searchMiss = SearchMiss.firstOccurrence("새빵집", "새빵집", SearchOutcome.EMPTY, NOW);
		given(searchMissRepository.findById(1L)).willReturn(Optional.of(searchMiss));
		TriageDecision decision = new TriageDecision(SearchMissVerdict.NEW_STORE_CANDIDATE, null, null, Map.of());

		commandService.recordDecision(1L, decision, true, NOW);

		assertThat(searchMiss.getStatus()).isEqualTo(SearchMissStatus.PENDING);
	}

	@Test
	@DisplayName("판정 도중 행이 사라졌으면 예외 없이 넘어간다")
	void recordDecision_missingRow() {
		given(searchMissRepository.findById(1L)).willReturn(Optional.empty());
		TriageDecision decision = new TriageDecision(SearchMissVerdict.JUNK, SearchMissStatus.IGNORED, null, Map.of());

		assertThatCode(() -> commandService.recordDecision(1L, decision, true, NOW)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("보관 기간이 지난 처리 완료 행을 지우고 지운 수를 돌려준다")
	void deleteHandledBefore() {
		given(searchMissRepository.deleteByHandledAtBefore(NOW.minusDays(90))).willReturn(3L);

		assertThat(commandService.deleteHandledBefore(NOW.minusDays(90))).isEqualTo(3L);
	}
}

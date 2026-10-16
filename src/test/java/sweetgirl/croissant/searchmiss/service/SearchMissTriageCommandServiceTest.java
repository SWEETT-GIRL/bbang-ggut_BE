package sweetgirl.croissant.searchmiss.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.searchmiss.entity.SearchMiss;
import sweetgirl.croissant.searchmiss.enums.SearchMissStatus;
import sweetgirl.croissant.searchmiss.enums.SearchMissVerdict;
import sweetgirl.croissant.searchmiss.repository.SearchMissRepository;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreNameAlias;
import sweetgirl.croissant.store.enums.StoreNameAliasSource;
import sweetgirl.croissant.store.enums.StoreStatus;
import sweetgirl.croissant.store.event.StoreSearchNameChangedEvent;
import sweetgirl.croissant.store.repository.StoreNameAliasRepository;
import sweetgirl.croissant.store.repository.StoreRepository;
import sweetgirl.croissant.store.service.search.SearchOutcome;

@ExtendWith(MockitoExtension.class)
class SearchMissTriageCommandServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 16, 12, 0);

	@Mock
	private SearchMissRepository searchMissRepository;

	@Mock
	private StoreNameAliasRepository storeNameAliasRepository;

	@Mock
	private StoreRepository storeRepository;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	@InjectMocks
	private SearchMissTriageCommandService commandService;

	@Test
	@DisplayName("없는 (가게, 별칭)만 자동 출처로 넣고 넣은 id 를 돌려준 뒤 자동완성 캐시를 비운다")
	void addAliases_insertsMissingOnly() {
		Store store = SearchMissTestFixtures.store(2L, "런던베이글뮤지엄", StoreStatus.ACTIVE);
		given(storeNameAliasRepository.existsByStore_IdAndAlias(1L, "런베뮤")).willReturn(true);
		given(storeNameAliasRepository.existsByStore_IdAndAlias(2L, "런베뮤")).willReturn(false);
		given(storeRepository.getReferenceById(2L)).willReturn(store);
		given(storeNameAliasRepository.save(any(StoreNameAlias.class))).willAnswer(invocation -> {
			StoreNameAlias alias = invocation.getArgument(0);
			ReflectionTestUtils.setField(alias, "id", 10L);
			return alias;
		});

		List<Long> inserted = commandService.addAliases("런베뮤", List.of(1L, 2L));

		assertThat(inserted).containsExactly(10L);
		ArgumentCaptor<StoreNameAlias> captor = ArgumentCaptor.forClass(StoreNameAlias.class);
		verify(storeNameAliasRepository).save(captor.capture());
		assertThat(captor.getValue().getSource()).isEqualTo(StoreNameAliasSource.SEARCH_MISS_AUTO);
		assertThat(captor.getValue().getAlias()).isEqualTo("런베뮤");
		verify(eventPublisher).publishEvent(StoreSearchNameChangedEvent.of("런베뮤"));
	}

	@Test
	@DisplayName("새로 넣은 별칭이 없으면 캐시를 건드리지 않는다")
	void addAliases_nothingInserted() {
		given(storeNameAliasRepository.existsByStore_IdAndAlias(1L, "런베뮤")).willReturn(true);

		List<Long> inserted = commandService.addAliases("런베뮤", List.of(1L));

		assertThat(inserted).isEmpty();
		verify(eventPublisher, never()).publishEvent(any());
	}

	@Test
	@DisplayName("별칭 되돌리기는 넘겨받은 id 만 지운다")
	void removeAliases() {
		commandService.removeAliases(List.of(10L, 11L));

		verify(storeNameAliasRepository).deleteAllById(List.of(10L, 11L));
	}

	@Test
	@DisplayName("auto-apply 면 판정을 남기고 처리 상태도 바꾼다")
	void recordDecision_autoApply() {
		SearchMiss searchMiss = SearchMiss.firstOccurrence("런베뮤", "런베뮤", SearchOutcome.EMPTY, NOW);
		given(searchMissRepository.findById(1L)).willReturn(Optional.of(searchMiss));
		TriageDecision decision = new TriageDecision(SearchMissVerdict.ALIAS_RESOLVED,
			SearchMissStatus.ALREADY_EXISTS, 7L, Map.of("reason", "별칭 추가 후 검색됨"));

		commandService.recordDecision(1L, decision, true, NOW);

		assertThat(searchMiss.getAiVerdict()).isEqualTo(SearchMissVerdict.ALIAS_RESOLVED);
		assertThat(searchMiss.getMatchedStoreId()).isEqualTo(7L);
		assertThat(searchMiss.getCountAtTriage()).isEqualTo(1L);
		assertThat(searchMiss.getAiTriagedAt()).isEqualTo(NOW);
		assertThat(searchMiss.getStatus()).isEqualTo(SearchMissStatus.ALREADY_EXISTS);
		assertThat(searchMiss.getHandledAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("shadow mode 면 판정만 남기고 처리 상태는 그대로 둔다")
	void recordDecision_shadowMode() {
		SearchMiss searchMiss = SearchMiss.firstOccurrence("런베뮤", "런베뮤", SearchOutcome.EMPTY, NOW);
		given(searchMissRepository.findById(1L)).willReturn(Optional.of(searchMiss));
		TriageDecision decision = new TriageDecision(SearchMissVerdict.JUNK, SearchMissStatus.IGNORED, null,
			Map.of());

		commandService.recordDecision(1L, decision, false, NOW);

		assertThat(searchMiss.getAiVerdict()).isEqualTo(SearchMissVerdict.JUNK);
		assertThat(searchMiss.getStatus()).isEqualTo(SearchMissStatus.PENDING);
	}

	@Test
	@DisplayName("사람이 봐야 하는 판정은 auto-apply 여도 PENDING 으로 둔다")
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
		TriageDecision decision = new TriageDecision(SearchMissVerdict.JUNK, SearchMissStatus.IGNORED, null,
			Map.of());

		assertThatCode(() -> commandService.recordDecision(1L, decision, true, NOW)).doesNotThrowAnyException();
	}
}

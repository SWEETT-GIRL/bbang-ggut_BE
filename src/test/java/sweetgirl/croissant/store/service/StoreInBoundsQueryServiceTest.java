package sweetgirl.croissant.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.common.util.MapBounds;
import sweetgirl.croissant.store.dto.projection.StoreInBoundsProjection;
import sweetgirl.croissant.store.dto.response.StoreSearchResponse;
import sweetgirl.croissant.store.dto.response.StoresInBoundsResponse;
import sweetgirl.croissant.store.repository.StoreRepository;
import sweetgirl.croissant.store.service.search.StoreSearchAssembler;
import sweetgirl.croissant.store.service.search.StoreSearchHit;

@ExtendWith(MockitoExtension.class)
class StoreInBoundsQueryServiceTest {

	private static final MapBounds SEONGSU = new MapBounds(37.53, 127.03, 37.56, 127.07);

	@Mock
	private StoreRepository storeRepository;

	@Mock
	private StoreSearchAssembler storeSearchAssembler;

	@InjectMocks
	private StoreInBoundsQueryService storeInBoundsQueryService;

	@Test
	@DisplayName("영역 안 가게를 받은 순서대로 검색 결과 모양으로 채우고, limit 만큼 찼으면 자르기 전 수를 따로 세어 준다")
	void getStoresInBounds_assemblesInOrderWithTotal() {
		given(storeRepository.findStoresInBounds(37.53, 127.03, 37.56, 127.07, 37.54, 127.05, 2))
			.willReturn(List.of(row(20L), row(10L)));
		given(storeRepository.countStoresInBounds(37.53, 127.03, 37.56, 127.07, StoreInBoundsQueryService.TOTAL_CAP))
			.willReturn(412L);
		List<StoreSearchResponse> assembled = List.of(store(20L), store(10L));
		given(storeSearchAssembler.assemble(anyList())).willReturn(assembled);

		StoresInBoundsResponse response = storeInBoundsQueryService.getStoresInBounds(SEONGSU, 37.54, 127.05, 2);

		assertThat(response.content()).isEqualTo(assembled);
		assertThat(response.total()).isEqualTo(412L);
		@SuppressWarnings("unchecked") ArgumentCaptor<List<StoreSearchHit>> hits = ArgumentCaptor.forClass(List.class);
		verify(storeSearchAssembler).assemble(hits.capture());
		assertThat(hits.getValue()).extracting(StoreSearchHit::storeId).containsExactly(20L, 10L);
	}

	@Test
	@DisplayName("limit 에 못 미치면 영역 안이 다 온 것이라 따로 세지 않는다 — total 은 받은 수다")
	void getStoresInBounds_skipsCountWhenUnderLimit() {
		given(storeRepository.findStoresInBounds(37.53, 127.03, 37.56, 127.07, 37.54, 127.05, 150))
			.willReturn(List.of(row(20L), row(10L)));
		given(storeSearchAssembler.assemble(anyList())).willReturn(List.of(store(20L), store(10L)));

		StoresInBoundsResponse response = storeInBoundsQueryService.getStoresInBounds(SEONGSU, 37.54, 127.05, null);

		assertThat(response.total()).isEqualTo(2L);
		verify(storeRepository, never()).countStoresInBounds(anyDouble(), anyDouble(), anyDouble(), anyDouble(),
			anyInt());
	}

	@Test
	@DisplayName("기준점이 없으면 영역 가운데에서 가까운 순이다")
	void getStoresInBounds_defaultsToBoxCenter() {
		given(storeRepository.findStoresInBounds(eq(37.53), eq(127.03), eq(37.56), eq(127.07),
			eq(SEONGSU.centerLat()), eq(SEONGSU.centerLng()), eq(150)))
			.willReturn(List.of());

		StoresInBoundsResponse response = storeInBoundsQueryService.getStoresInBounds(SEONGSU, null, null, null);

		assertThat(response.content()).isEmpty();
		assertThat(response.total()).isZero();
		verify(storeSearchAssembler, never()).assemble(anyList());
	}

	@Test
	@DisplayName("limit 은 1~300 으로 자른다 — 비로그인 엔드포인트라 상한이 있다")
	void getStoresInBounds_clampsLimit() {
		given(storeRepository.findStoresInBounds(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
			anyDouble(), anyInt())).willReturn(List.of());

		storeInBoundsQueryService.getStoresInBounds(SEONGSU, null, null, 1000);
		storeInBoundsQueryService.getStoresInBounds(SEONGSU, null, null, 0);

		verify(storeRepository).findStoresInBounds(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
			anyDouble(), eq(300));
		verify(storeRepository).findStoresInBounds(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
			anyDouble(), eq(1));
	}

	@Test
	@DisplayName("기준점은 위도·경도를 함께 줘야 한다 — 하나만 오면 400")
	void getStoresInBounds_rejectsHalfCenter() {
		assertThatThrownBy(() -> storeInBoundsQueryService.getStoresInBounds(SEONGSU, 37.54, null, null))
			.isInstanceOf(IllegalArgumentException.class);
		verify(storeRepository, never()).findStoresInBounds(anyDouble(), anyDouble(), anyDouble(), anyDouble(),
			anyDouble(), anyDouble(), anyInt());
	}

	@Test
	@DisplayName("기준점이 WGS84 범위 밖이면 400")
	void getStoresInBounds_rejectsOutOfRangeCenter() {
		assertThatThrownBy(() -> storeInBoundsQueryService.getStoresInBounds(SEONGSU, 95.0, 127.0, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static StoreInBoundsProjection row(Long storeId) {
		return () -> storeId;
	}

	private static StoreSearchResponse store(Long id) {
		return new StoreSearchResponse(id, "가게" + id, "서울 성동구", null, null, null, "127.05", "37.54",
			List.of(), List.of());
	}
}

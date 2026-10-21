package sweetgirl.croissant.store.service.search.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.store.event.StorePopularityRefreshedEvent;
import sweetgirl.croissant.store.event.StoreSearchNameChangedEvent;
import sweetgirl.croissant.store.event.StoreTrendingBaselineRefreshedEvent;

@ExtendWith(MockitoExtension.class)
class AutocompleteCacheEvictListenerTest {

	@Mock
	private AutocompleteCacheInvalidator invalidator;

	@InjectMocks
	private AutocompleteCacheEvictListener listener;

	@Test
	@DisplayName("가게 이름이 바뀌면 그 이름들로 걸리는 키만 지운다")
	void storeNameChanged_evictsByNames() {
		listener.onStoreSearchNameChanged(StoreSearchNameChangedEvent.of("옛이름", "새이름"));

		verify(invalidator).evictByNames(List.of("옛이름", "새이름"));
	}

	@Test
	@DisplayName("인기도를 다시 계산하면 순서가 통째로 바뀌므로 전체를 비운다")
	void popularityRefreshed_evictsAll() {
		listener.onStorePopularityRefreshed(new StorePopularityRefreshedEvent(45_339));

		verify(invalidator).evictAll();
	}

	@Test
	@DisplayName("평소 수준을 다시 계산하면 스테디 순서가 바뀌므로 전체를 비운다")
	void trendingBaselineRefreshed_evictsAll() {
		listener.onStoreTrendingBaselineRefreshed(new StoreTrendingBaselineRefreshedEvent(10));

		verify(invalidator).evictAll();
	}

	@Test
	@DisplayName("이벤트는 null 이름을 거르고 같은 이름은 한 번만 담는다")
	void event_filtersNullAndDuplicateNames() {
		assertThat(StoreSearchNameChangedEvent.of("빵집", null, "빵집").names())
			.containsExactly("빵집");
	}
}

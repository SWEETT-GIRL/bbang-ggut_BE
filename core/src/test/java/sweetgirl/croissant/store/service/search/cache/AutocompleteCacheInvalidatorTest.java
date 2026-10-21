package sweetgirl.croissant.store.service.search.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import sweetgirl.croissant.store.config.StoreAutocompleteCacheProperties;

@ExtendWith(MockitoExtension.class)
class AutocompleteCacheInvalidatorTest {

	@Mock
	private StringRedisTemplate redisTemplate;

	private final StoreAutocompleteCacheProperties properties = new StoreAutocompleteCacheProperties();
	private AutocompleteCacheInvalidator invalidator;

	@BeforeEach
	void setUp() {
		properties.setEnabled(true);
		invalidator = new AutocompleteCacheInvalidator(redisTemplate, properties);
	}

	@Test
	@DisplayName("이름의 모든 부분 문자열이 영향받는 검색어다 — '폼포미'면 6개, 자동완성이 '%q%' 로 찾기 때문")
	void affectedKeys_coversEverySubstring() {
		Set<String> keys = AutocompleteCacheInvalidator.affectedKeys(List.of("폼포미"));

		assertThat(keys).containsExactlyInAnyOrder("ac:폼", "ac:폼포", "ac:폼포미", "ac:포", "ac:포미", "ac:미");
	}

	@Test
	@DisplayName("검색어와 같은 규칙으로 정규화한다 — 공백·특수문자는 빼고 영문은 소문자")
	void affectedKeys_normalizesLikeSearchQuery() {
		Set<String> keys = AutocompleteCacheInvalidator.affectedKeys(List.of("A 빵!"));

		assertThat(keys).containsExactlyInAnyOrder("ac:a", "ac:a빵", "ac:빵");
	}

	@Test
	@DisplayName("옛 이름·새 이름에 겹치는 부분 문자열은 한 번만 지운다")
	void affectedKeys_deduplicatesAcrossNames() {
		Set<String> keys = AutocompleteCacheInvalidator.affectedKeys(List.of("빵집", "빵가게"));

		assertThat(keys).filteredOn("ac:빵"::equals).hasSize(1);
		// 빵집 3개 + 빵가게 6개 − 겹치는 '빵' 1개
		assertThat(keys).hasSize(8);
	}

	@Test
	@DisplayName("이름으로 걸리는 키만 한 번에 지운다 — 다른 검색어 캐시는 그대로 남는다")
	@SuppressWarnings("unchecked")
	void evictByNames_unlinksOnlyAffectedKeys() {
		int evicted = invalidator.evictByNames(List.of("폼포미"));

		ArgumentCaptor<Collection<String>> keys = ArgumentCaptor.forClass(Collection.class);
		verify(redisTemplate).unlink(keys.capture());
		assertThat(keys.getValue()).hasSize(6).doesNotContain("ac:성심");
		assertThat(evicted).isEqualTo(6);
	}

	@Test
	@DisplayName("Redis 가 죽어 있어도 던지지 않는다 — 가게 등록·수정 요청이 캐시 때문에 깨지면 안 된다")
	void evictByNames_swallowsRedisFailure() {
		given(redisTemplate.unlink(anyCollection())).willThrow(new RedisConnectionFailureException("down"));

		assertThat(invalidator.evictByNames(List.of("폼포미"))).isZero();
	}

	@Test
	@DisplayName("꺼져 있으면 Redis 를 건드리지 않는다")
	void disabled_doesNothing() {
		properties.setEnabled(false);

		assertThat(invalidator.evictByNames(List.of("폼포미"))).isZero();
		assertThat(invalidator.evictAll()).isZero();
		verifyNoInteractions(redisTemplate);
	}

	@Test
	@DisplayName("전체 비우기는 ac:* 만 SCAN 으로 훑어 지운다")
	@SuppressWarnings("unchecked")
	void evictAll_scansAndUnlinksAutocompleteKeys() {
		Cursor<String> cursor = new ListCursor(List.of("ac:성", "ac:성심", "ac:폼"));
		given(redisTemplate.scan(any(ScanOptions.class))).willReturn(cursor);
		given(redisTemplate.unlink(anyCollection())).willReturn(3L);

		long evicted = invalidator.evictAll();

		ArgumentCaptor<ScanOptions> options = ArgumentCaptor.forClass(ScanOptions.class);
		verify(redisTemplate).scan(options.capture());
		assertThat(options.getValue().getPattern()).isEqualTo("ac:*");
		assertThat(evicted).isEqualTo(3);
	}

	/** 테스트용 Cursor — 주어진 키를 차례로 돌려준다 */
	private static final class ListCursor implements Cursor<String> {

		private final List<String> keys;
		private int position;

		ListCursor(List<String> keys) {
			this.keys = new ArrayList<>(keys);
		}

		@Override
		public boolean hasNext() {
			return position < keys.size();
		}

		@Override
		public String next() {
			return keys.get(position++);
		}

		@Override
		public CursorId getId() {
			return CursorId.initial();
		}

		@Override
		public long getCursorId() {
			return 0;
		}

		@Override
		public boolean isClosed() {
			return false;
		}

		@Override
		public long getPosition() {
			return position;
		}

		@Override
		public void close() {
			// 닫을 자원이 없다
		}
	}
}

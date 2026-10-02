package sweetgirl.croissant.config.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;

/** 캐시가 히트·미스를 세는지 — /actuator/prometheus 의 cache_gets_total 이 여기서 나온다. 안 세면 지표가 0 으로 찍혀 "캐시가 안 맞는다"로 읽힌다. */
class CacheConfigTest {

	@Test
	@DisplayName("세 캐시 모두 미스 한 번·히트 한 번을 그대로 센다")
	void everyCacheRecordsStats() {
		SimpleCacheManager manager = (SimpleCacheManager)new CacheConfig().cacheManager();
		manager.afterPropertiesSet();

		for (String name : List.of(CacheConfig.BREAD_BOARD, CacheConfig.BREAD_BOARD_CATEGORIES,
			CacheConfig.STORE_AUTOCOMPLETE)) {
			CaffeineCache cache = (CaffeineCache)manager.getCache(name);
			assertThat(cache).as(name).isNotNull();

			cache.get("소금");
			cache.put("소금", List.of("소금빵"));
			cache.get("소금");

			assertThat(cache.getNativeCache().stats().missCount()).as(name + " miss").isEqualTo(1);
			assertThat(cache.getNativeCache().stats().hitCount()).as(name + " hit").isEqualTo(1);
		}
	}
}

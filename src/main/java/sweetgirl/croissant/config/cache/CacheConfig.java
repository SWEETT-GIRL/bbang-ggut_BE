package sweetgirl.croissant.config.cache;

import java.time.Duration;
import java.util.List;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * 짧은 인메모리 캐시 — 같은 요청이 몰리는 집계·자동완성만. 서버가 한 대라 인스턴스 간 불일치는 없다.
 * 캐시마다 TTL 이 달라 하나의 spec 이 아니라 캐시별로 만든다.
 */
@Configuration
@EnableCaching
public class CacheConfig {

	/** 빵집 판(종류·limit 별) — 표가 바뀌어도 1분은 같은 순위를 본다 */
	public static final String BREAD_BOARD = "breadBoard";
	/** 빵집 탭 목록(10명 이상 종류) */
	public static final String BREAD_BOARD_CATEGORIES = "breadBoardCategories";
	/** 가게 자동완성(검색어·size 별) — 가장 느리고 가장 자주 불리는 조회 */
	public static final String STORE_AUTOCOMPLETE = "storeAutocomplete";

	@Bean
	public CacheManager cacheManager() {
		SimpleCacheManager manager = new SimpleCacheManager();
		manager.setCaches(List.of(
			new CaffeineCache(BREAD_BOARD, Caffeine.newBuilder()
				.expireAfterWrite(Duration.ofMinutes(1)).maximumSize(500).build()),
			new CaffeineCache(BREAD_BOARD_CATEGORIES, Caffeine.newBuilder()
				.expireAfterWrite(Duration.ofMinutes(1)).maximumSize(4).build()),
			new CaffeineCache(STORE_AUTOCOMPLETE, Caffeine.newBuilder()
				.expireAfterWrite(Duration.ofSeconds(30)).maximumSize(5_000).build())));
		return manager;
	}
}

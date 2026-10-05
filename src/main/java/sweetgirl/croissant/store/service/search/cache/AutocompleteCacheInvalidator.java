package sweetgirl.croissant.store.service.search.cache;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.store.config.StoreAutocompleteCacheProperties;
import sweetgirl.croissant.store.service.search.SearchTextNormalizer;

/**
 * 자동완성 캐시 무효화. 가게 이름이 바뀌면 그 이름으로 걸릴 수 있는 검색어 키만 지운다.
 *
 * <p>자동완성은 {@code name_normalized LIKE '%q%'} 로 찾으므로 이름의 <b>모든 부분 문자열</b>이 곧 결과가 달라질 수 있는 검색어다 —
 * '폼포미베이커리'(7글자)면 28개. 캐시 전체를 비우면 가게 하나 때문에 모든 검색어가 한꺼번에 DB 로 몰린다.
 *
 * <p>실패해도 던지지 않는다 — 가게 등록·수정 요청을 깨뜨리는 것보다 TTL 까지 옛 결과가 보이는 편이 낫다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutocompleteCacheInvalidator {

	private static final int SCAN_BATCH_SIZE = 500;

	private final StringRedisTemplate redisTemplate;
	private final StoreAutocompleteCacheProperties properties;

	/**
	 * @param names 바뀌기 전·후 이름(원문). 정규화는 여기서 한다.
	 * @return 지우라고 보낸 키 수
	 */
	public int evictByNames(Collection<String> names) {
		if (!properties.isEnabled()) {
			return 0;
		}
		Set<String> keys = affectedKeys(names);
		if (keys.isEmpty()) {
			return 0;
		}
		try {
			redisTemplate.unlink(keys);
			return keys.size();
		} catch (RuntimeException exception) {
			log.warn("[자동완성 캐시] 무효화 실패 — TTL 까지 옛 결과가 남는다 names={} cause={}", names, exception.toString());
			return 0;
		}
	}

	/**
	 * 캐시를 모두 지운다. 앱 밖에서 바뀌는 데이터(데이터 파이프라인이 넣는 별칭, Flyway 로 바뀌는 지역)와 결과 순서를 통째로 바꾸는 인기도
	 * 갱신 뒤에 쓴다. KEYS 대신 SCAN 으로 끊어 지워 Redis 를 오래 잡지 않는다.
	 *
	 * @return 지운 키 수
	 */
	public long evictAll() {
		if (!properties.isEnabled()) {
			return 0;
		}
		ScanOptions options = ScanOptions.scanOptions().match(AutocompleteCacheKeys.PREFIX + "*")
			.count(SCAN_BATCH_SIZE).build();
		long evicted = 0;
		List<String> batch = new ArrayList<>(SCAN_BATCH_SIZE);
		try (Cursor<String> cursor = redisTemplate.scan(options)) {
			while (cursor.hasNext()) {
				batch.add(cursor.next());
				if (batch.size() == SCAN_BATCH_SIZE) {
					evicted += unlink(batch);
				}
			}
			evicted += unlink(batch);
			return evicted;
		} catch (RuntimeException exception) {
			log.warn("[자동완성 캐시] 전체 비우기 중단 evicted={} cause={}", evicted, exception.toString());
			return evicted;
		}
	}

	private long unlink(List<String> batch) {
		if (batch.isEmpty()) {
			return 0;
		}
		Long unlinked = redisTemplate.unlink(batch);
		batch.clear();
		return unlinked != null ? unlinked : 0;
	}

	static Set<String> affectedKeys(Collection<String> names) {
		Set<String> keys = new LinkedHashSet<>();
		for (String name : names) {
			String normalized = SearchTextNormalizer.normalize(name);
			for (int start = 0; start < normalized.length(); start++) {
				for (int end = start + 1; end <= normalized.length(); end++) {
					keys.add(AutocompleteCacheKeys.of(normalized.substring(start, end)));
				}
			}
		}
		return keys;
	}
}

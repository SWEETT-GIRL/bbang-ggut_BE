package sweetgirl.croissant.config.cache;

import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

import lombok.extern.slf4j.Slf4j;

/**
 * 캐시(Redis) 오류를 삼키고 원본 메서드(DB 조회)로 흘려보낸다.
 *
 * 캐시는 응답을 빠르게 하는 장치이지 정합성의 원천이 아니다. Redis가 죽거나 느려졌다고
 * 코스 목록이 500으로 죽어서는 안 되므로, 캐시 계층의 예외는 여기서 끝낸다.
 */
@Slf4j
public class FailOpenCacheErrorHandler implements CacheErrorHandler {

	@Override
	public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
		log.warn("캐시 조회 실패 — DB로 우회 [cache: {}, key: {}]: {}", cache.getName(), key, exception.toString());
	}

	@Override
	public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
		log.warn("캐시 저장 실패 — 무시 [cache: {}, key: {}]: {}", cache.getName(), key, exception.toString());
	}

	@Override
	public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
		log.warn("캐시 무효화 실패 — TTL 만료에 맡김 [cache: {}, key: {}]: {}", cache.getName(), key,
			exception.toString());
	}

	@Override
	public void handleCacheClearError(RuntimeException exception, Cache cache) {
		log.warn("캐시 전체 무효화 실패 — TTL 만료에 맡김 [cache: {}]: {}", cache.getName(), exception.toString());
	}
}

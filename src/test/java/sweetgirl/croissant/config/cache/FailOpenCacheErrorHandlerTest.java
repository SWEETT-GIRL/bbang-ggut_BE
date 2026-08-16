package sweetgirl.croissant.config.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import sweetgirl.croissant.course.code.CourseCacheNames;
import sweetgirl.croissant.course.repository.CourseImageFileRepository;
import sweetgirl.croissant.course.repository.CourseProgressRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.course.repository.SavedCourseRepository;
import sweetgirl.croissant.course.service.CourseQueryService;

/** 캐시 계층이 죽어도(Redis 장애 등) 조회는 DB로 fail-open 해야 한다. */
@SpringJUnitConfig
@Import({CacheConfig.class, CourseQueryService.class})
@MockitoBean(types = {CourseRepository.class, CourseStoreRepository.class, SavedCourseRepository.class,
	CourseImageFileRepository.class, CourseProgressRepository.class})
class FailOpenCacheErrorHandlerTest {

	/** 모든 연산이 Redis 연결 실패처럼 터지는 캐시. */
	static class BrokenCache extends ConcurrentMapCache {
		BrokenCache(String name) {
			super(name);
		}

		@Override
		protected Object lookup(Object key) {
			throw new IllegalStateException("redis down");
		}

		@Override
		public void put(Object key, Object value) {
			throw new IllegalStateException("redis down");
		}

		@Override
		public void evict(Object key) {
			throw new IllegalStateException("redis down");
		}

		@Override
		public void clear() {
			throw new IllegalStateException("redis down");
		}
	}

	@Configuration
	static class BrokenCacheConfig {
		@Bean
		CacheManager cacheManager() {
			SimpleCacheManager cacheManager = new SimpleCacheManager();
			cacheManager.setCaches(List.of(new BrokenCache(CourseCacheNames.PUBLIC_LIST)));
			return cacheManager;
		}
	}

	@Autowired
	private CourseQueryService courseQueryService;
	@Autowired
	private CourseRepository courseRepository;

	@Test
	@DisplayName("캐시 조회·저장이 실패해도 예외 없이 DB 결과를 돌려준다")
	void getCurationCourses_fallsBackToDb_whenCacheBroken() {
		when(courseRepository.findAllPublicOrderedByPinAndSavedCount()).thenReturn(List.of());

		assertThat(courseQueryService.getCurationCourses()).isEmpty();
		assertThat(courseQueryService.getCurationCourses()).isEmpty();

		verify(courseRepository, times(2)).findAllPublicOrderedByPinAndSavedCount();
	}

	@Test
	@DisplayName("무효화 실패도 삼킨다 — TTL 만료에 맡긴다")
	void evictAndClear_doNotThrow() {
		FailOpenCacheErrorHandler handler = new FailOpenCacheErrorHandler();
		Cache cache = new BrokenCache(CourseCacheNames.PUBLIC_LIST);

		handler.handleCacheEvictError(new IllegalStateException("redis down"), cache, "all");
		handler.handleCacheClearError(new IllegalStateException("redis down"), cache);
	}
}

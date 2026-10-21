package sweetgirl.croissant.store.service.search.cache;

/**
 * 자동완성 캐시의 Redis 키 규칙. 검색어 하나에 Hash 하나를 두고 size 별 결과를 field 로 담는다 — 무효화 때 {@code DEL ac:폼} 한 번으로
 * 그 검색어의 모든 size 결과가 함께 지워진다.
 */
final class AutocompleteCacheKeys {

	static final String PREFIX = "ac:";
	static final String LOCK_PREFIX = "lock:ac:";

	private AutocompleteCacheKeys() {
		// 정적 유틸리티
	}

	/** @param normalizedQuery {@code SearchTextNormalizer} 로 정규화한 검색어 */
	static String of(String normalizedQuery) {
		return PREFIX + normalizedQuery;
	}

	static String lockOf(String normalizedQuery) {
		return LOCK_PREFIX + normalizedQuery;
	}

	static String field(int size) {
		return String.valueOf(size);
	}
}

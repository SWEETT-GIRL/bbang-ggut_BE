package sweetgirl.croissant.admin.dto.response;

/** @param evictedKeyCount 지운 자동완성 캐시 키(검색어) 수 */
public record AdminAutocompleteCacheEvictResponse(long evictedKeyCount) {
}

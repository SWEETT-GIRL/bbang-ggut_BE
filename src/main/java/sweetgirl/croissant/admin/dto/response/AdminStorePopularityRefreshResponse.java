package sweetgirl.croissant.admin.dto.response;

/** @param updatedStoreCount popularity_score 를 다시 계산한 가게 수 */
public record AdminStorePopularityRefreshResponse(int updatedStoreCount) {
}

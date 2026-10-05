package sweetgirl.croissant.admin.dto.response;

/** @param updatedStoreCount 값이 바뀐 가게 수(순위표 갱신이면 순위표에 오른 가게 수, 다른 인스턴스가 계산 중이면 -1) */
public record AdminStoreTrendingRefreshResponse(int updatedStoreCount) {
}

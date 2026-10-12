package sweetgirl.croissant.reward.dto.response;

import java.util.List;

/**
 * 빵집 담기 결과.
 *
 * @param rewards 이 담기로 생긴 보상. 이미 담은 가게이거나 예전에 담아 받은 적이 있으면 비어 있다
 */
public record SaveStoreResponse(List<RewardItemResponse> rewards) {
}

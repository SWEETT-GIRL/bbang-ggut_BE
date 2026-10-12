package sweetgirl.croissant.reward.dto.response;

import java.util.List;

/**
 * 구매 결과.
 *
 * @param crumbs  사고 난 뒤의 잔액
 * @param rewards 이 구매로 움직인 부스러기(음수 한 칸)
 */
public record ShopPurchaseResponse(int crumbs, List<RewardItemResponse> rewards) {
}

package sweetgirl.croissant.reward.dto.response;

/**
 * 어드민 지급·회수 결과.
 *
 * @param applied 이번 요청으로 실제 반영됐는가. 같은 requestId 의 재전송이거나 회수할 잔액이 없으면 false
 * @param amount  반영된 양. 회수는 잔액까지만 빼므로 요청한 값보다 작을 수 있다
 * @param crumbs  반영 뒤 잔액
 */
public record AdminCrumbAdjustResponse(boolean applied, int amount, int crumbs) {
}

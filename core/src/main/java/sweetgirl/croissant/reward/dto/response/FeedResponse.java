package sweetgirl.croissant.reward.dto.response;

import java.util.List;

/**
 * 밥 룰렛 결과.
 *
 * @param wedge       룰렛이 멈춘 칸(0 부터, 시계 방향 — {@code summary.feed.wedges} 의 인덱스)
 * @param crumbs      오늘 받은 양
 * @param alreadyDone 오늘 이미 돌렸다. 이때 {@code rewards} 는 비어 있고 나머지는 오늘 결과 그대로다
 */
public record FeedResponse(int wedge, int crumbs, boolean alreadyDone, List<RewardItemResponse> rewards) {
}

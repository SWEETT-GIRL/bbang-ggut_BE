package sweetgirl.croissant.reward.dto.response;

import java.util.List;

/**
 * 출석 결과.
 *
 * @param boardDay    오늘 찍힌 칸(1~7)
 * @param crumbs      오늘 받은 양
 * @param alreadyDone 오늘 이미 출석했다. 이때 {@code rewards} 는 비어 있고 나머지는 오늘 결과 그대로다
 */
public record AttendanceResponse(int boardDay, int crumbs, boolean alreadyDone, List<RewardItemResponse> rewards) {
}

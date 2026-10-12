package sweetgirl.croissant.reward.dto.response;

import java.util.List;

/**
 * 빵투어의 가게를 다녀왔다고 표시한 결과.
 *
 * @param recordId 이 방문에 이어진 빵 기록. 한 번 탭으로 만든 가벼운 기록이면 방금 생긴 기록이다
 * @param rewards  이 표시로 생긴 보상(기록 · 다녀옴 · 완주 · 사진 완주). 앱은 배열 순서대로 연출한다
 */
public record TourRewardResponse(Long recordId, List<RewardItemResponse> rewards) {
}

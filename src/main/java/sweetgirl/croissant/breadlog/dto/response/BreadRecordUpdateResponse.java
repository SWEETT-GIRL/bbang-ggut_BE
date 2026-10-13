package sweetgirl.croissant.breadlog.dto.response;

import java.util.List;

import sweetgirl.croissant.reward.dto.response.RewardItemResponse;

/**
 * 기록 수정 결과.
 *
 * @param rewards 이 수정으로 생긴 보상(나중에 사진을 붙여 받는 보너스). 없으면 비어 있다
 */
public record BreadRecordUpdateResponse(List<RewardItemResponse> rewards) {
}

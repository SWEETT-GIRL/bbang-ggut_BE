package sweetgirl.croissant.breadlog.dto.response;

import java.time.LocalDateTime;

/**
 * 랭킹 "내 지역".
 *
 * @param sido             고른 시·도. 아직 안 골랐으면 null
 * @param nextChangeableAt 다음에 바꿀 수 있는 시각(Asia/Seoul). 지금 바꿀 수 있으면(아직 안 골랐어도) null
 */
public record RankingRegionResponse(String sido, LocalDateTime nextChangeableAt) {
}

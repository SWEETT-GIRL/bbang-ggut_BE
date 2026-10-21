package sweetgirl.croissant.recommendation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 후보가 바닥났을 때 제안하는 다음 동네(#661 다시 추천 계약). 같은 도장 칸의 다른 동네가 먼저, 그다음 같은 시·도의 다음 도장 칸이다.
 * areaId 로 그대로 추천을 요청하면 서버는 동네를 다시 고르지 않고 이 행을 쓴다.
 */
public record NextAreaResponse(
		@Schema(description = "상권(동네) 행 ID") Long areaId,
		@Schema(description = "동네 이름", example = "중문") String name,
		@Schema(description = "그 동네 후보 가게 수") int storeCount) {
}

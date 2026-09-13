package sweetgirl.croissant.recommendation.dto.request;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 상권 하나로 코스를 짜 달라는 요청 (설계 §6). 여정 값(entryScreen · sessionEvents)은 계산에만 쓰고 저장하지 않는다. */
public record RecommendationRequest(
		@Schema(description = "상권 ID") @NotNull(message = "상권 ID는 필수입니다.") Long areaId,
		@Schema(description = "가게 수. 기본 4, 1~6") Integer storeCount,
		@Schema(description = "담을 일차. 없으면 첫 빈 일차") Integer day,
		@Schema(description = "카드가 뜬 화면") @NotBlank(message = "진입 화면은 필수입니다.") String entryScreen,
		@Schema(description = "카드 직전 이벤트 이름 3개") List<String> sessionEvents) {
}

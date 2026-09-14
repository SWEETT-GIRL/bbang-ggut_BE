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
		@Schema(description = "카드 직전 이벤트 이름 3개") List<String> sessionEvents,
		@Schema(description = "다시 받기: 지금 보고 있는 추천 ID. 있으면 그 추천의 상권·일차에서 안 본 가게로 새 묶음") Long previousRecommendationId,
		@Schema(description = "areaId 를 동네 행으로 그대로 쓴다 — 다음 동네 제안(nextArea)에서 온 요청. 칩에서 온 요청은 비운다") Boolean exactArea) {
	/** 다시 받기가 아닌 요청 — 기존 호출 모양을 그대로 둔다. */
	public RecommendationRequest(Long areaId, Integer storeCount, Integer day, String entryScreen,
			List<String> sessionEvents) {
		this(areaId, storeCount, day, entryScreen, sessionEvents, null, null);
	}

	public RecommendationRequest(Long areaId, Integer storeCount, Integer day, String entryScreen,
			List<String> sessionEvents, Long previousRecommendationId) {
		this(areaId, storeCount, day, entryScreen, sessionEvents, previousRecommendationId, null);
	}

	/** nextArea 의 areaId 로 온 요청인가 — 대표 행 id 는 칩 id 와 같아 표시 없이는 구분할 수 없다. */
	public boolean isExactArea() {
		return Boolean.TRUE.equals(exactArea);
	}
}

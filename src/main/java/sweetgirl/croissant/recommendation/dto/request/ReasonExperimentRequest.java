package sweetgirl.croissant.recommendation.dto.request;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import sweetgirl.croissant.recommendation.enums.ReasonPromptVariant;

/**
 * 이유 문장 실험 요청 (#662, 관리자 전용).
 *
 * @param memberId 취향 프로필을 읽어 올 회원. <b>프롬프트에는 들어가지 않는다</b> — 축·태그·종류만 간다
 * @param areaId 어느 상권의 추천으로 실험할지
 * @param models {@code 벤더:모델} 목록. 비면 설정값
 * @param variants 프롬프트 변형. 비면 설정값
 * @param blind 라벨 없이 문장만 섞어 돌려줄지. 룰 문구도 한 줄 섞인다
 */
public record ReasonExperimentRequest(
		@NotNull(message = "회원 ID는 필수입니다.") Long memberId,
		@NotNull(message = "상권 ID는 필수입니다.") Long areaId,
		@Schema(description = "추천 가게 수. 비면 기본값") Integer storeCount,
		@Schema(example = "[\"GEMINI:gemini-3.5-flash-lite\"]") List<String> models,
		List<ReasonPromptVariant> variants,
		Boolean blind) {

	public boolean isBlind() {
		return Boolean.TRUE.equals(blind);
	}
}

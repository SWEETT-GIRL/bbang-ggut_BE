package sweetgirl.croissant.personalization.dto.request;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

/** 빵BTI 검사 응답 — 추천 카드의 폴백 4문항(축마다 하나) 또는 8문항(축마다 둘). */
public record BbtiQuizRequest(
		/*
		 * 검사 판. 헤더가 아니라 본문에 두는 이유는 저장되는 답과 같이 다녀야 하기 때문이다 — 헤더는 프록시·캐시를
		 * 지나며 유실될 수 있고, 그러면 어떤 판의 답인지 모르는 행이 남는다. 안 보내면 NULL 로 적고 답은 그대로
		 * 저장한다(막지 않는다) — 선택지 이름만으로도 지금은 판이 드러나고, 옛 앱을 400 으로 막아 봐야 사용자가
		 * 할 수 있는 일이 없다.
		 */
		@Schema(description = "검사 판 — 1 은 축마다 두 갈래(A/B), 2 는 갈래 이름 11개", example = "2") Short quizVersion,
		@NotEmpty(message = "검사 응답은 비어 있을 수 없습니다.") List<@Valid BbtiQuizAnswer> answers) {
}

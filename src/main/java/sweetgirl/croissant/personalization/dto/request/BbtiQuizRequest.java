package sweetgirl.croissant.personalization.dto.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

/** 빵BTI 검사 응답 — 추천 카드의 폴백 4문항(축마다 하나) 또는 8문항(축마다 둘). */
public record BbtiQuizRequest(
		@NotEmpty(message = "검사 응답은 비어 있을 수 없습니다.") List<@Valid BbtiQuizAnswer> answers) {
}

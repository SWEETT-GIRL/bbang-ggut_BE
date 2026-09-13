package sweetgirl.croissant.personalization.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import sweetgirl.croissant.personalization.enums.QuizAxis;
import sweetgirl.croissant.personalization.enums.QuizChoice;

/** 빵BTI 문항 하나의 답. A 는 −1 극(달콤·폭신·꽉 찬·고전), B 는 +1 극(담백·바삭·심플·신상). */
public record BbtiQuizAnswer(
		@Schema(description = "축", example = "taste") @NotNull(message = "축은 필수입니다.") QuizAxis axis,
		@Schema(description = "고른 극", example = "B") @NotNull(message = "선택은 필수입니다.") QuizChoice choice) {
}

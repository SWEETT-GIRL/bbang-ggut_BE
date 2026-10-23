package sweetgirl.croissant.comment.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 앱이 고른 멘션 — 본문 파싱은 서버가 하지 않는다. 오프셋은 본문(문자 단위) 기준. */
public record MentionRequest(@NotNull Long memberId, @Min(0) int start, @Min(1) int length) {
}

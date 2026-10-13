package sweetgirl.croissant.reward.dto.response;

import java.time.LocalDate;

/** 받은 빵댕이 편지 한 통. 안 연 편지도 글은 내려간다 — 봉투를 여는 연출은 앱이 한다. */
public record BbangdaengLetterResponse(Long id, LocalDate weekStart, String body, boolean opened) {
}

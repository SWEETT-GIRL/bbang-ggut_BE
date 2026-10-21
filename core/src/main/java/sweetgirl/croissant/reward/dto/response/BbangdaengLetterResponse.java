package sweetgirl.croissant.reward.dto.response;

import java.time.LocalDate;

/**
 * 받은 빵댕이 편지 한 통.
 *
 * @param body   연 편지의 글. 안 연 편지는 null — 글은 열 때({@code POST /letters/{id}/open}) 받는다
 * @param crumbs 연 편지는 그때 받은 부스러기(장부값), 안 연 편지는 지금 열면 받을 양(규칙값)
 */
public record BbangdaengLetterResponse(Long id, LocalDate weekStart, String body, boolean opened, int crumbs) {
}

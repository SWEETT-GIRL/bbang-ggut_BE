package sweetgirl.croissant.review.dto.response;

import java.time.LocalDateTime;

public record ReviewCreateResponse(Long reviewId, LocalDateTime createdAt) {
}

package sweetgirl.croissant.review.dto.response;

import java.util.List;

public record MyReviewListResponse(
		List<ReviewDetailResponse> content,
		boolean hasNext) {
}

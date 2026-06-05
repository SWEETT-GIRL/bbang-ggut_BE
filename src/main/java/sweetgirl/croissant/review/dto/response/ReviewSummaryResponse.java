package sweetgirl.croissant.review.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record ReviewSummaryResponse(
		Long menuId,
		BigDecimal averageRating,
		long reviewCount,
		List<MenuReviewResponse> previewReviews) {
}

package sweetgirl.croissant.review.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record ReviewListResponse(
		Long menuId,
		BigDecimal averageRating,
		long reviewCount,
		List<MenuReviewResponse> content,
		boolean hasNext) {
}

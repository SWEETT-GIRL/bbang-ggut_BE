package sweetgirl.croissant.review.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import sweetgirl.croissant.review.enums.TextureTag;

public record MenuReviewResponse(
		Long id,
		BigDecimal rating,
		String repurchaseIntent,
		String sweetness,
		String fillingAmount,
		List<TextureTag> textureTags,
		String content,
		int recommendCount,
		int commentCount,
		boolean isRecommended,
		boolean canEdit,
		boolean canDelete,
		ReviewAuthorResponse author,
		List<ReviewPhotoResponse> photos,
		LocalDateTime createdAt) {
}

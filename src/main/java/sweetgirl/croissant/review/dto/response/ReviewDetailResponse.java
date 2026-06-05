package sweetgirl.croissant.review.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import sweetgirl.croissant.review.enums.TextureTag;

public record ReviewDetailResponse(
		Long id,
		Long menuId,
		String menuName,
		Long storeId,
		String storeName,
		BigDecimal rating,
		String repurchaseIntent,
		String sweetness,
		String fillingAmount,
		List<TextureTag> textureTags,
		String content,
		int likeCount,
		int commentCount,
		boolean isLiked,
		boolean canEdit,
		boolean canDelete,
		ReviewAuthorResponse author,
		List<ReviewPhotoResponse> photos,
		LocalDateTime createdAt,
		LocalDateTime updatedAt) {
}

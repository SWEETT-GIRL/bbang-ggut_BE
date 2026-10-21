package sweetgirl.croissant.review.dto.response;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewAuthorResponse(
		Long id,
		String name,
		Long reviewCount,
		BigDecimal averageRating,
		String badge,
		String avatarColor) {
}

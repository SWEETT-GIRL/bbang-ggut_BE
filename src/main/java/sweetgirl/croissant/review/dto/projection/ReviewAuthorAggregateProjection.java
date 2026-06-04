package sweetgirl.croissant.review.dto.projection;

public interface ReviewAuthorAggregateProjection {
	Long getMemberId();

	Long getReviewCount();

	Double getAverageRating();
}

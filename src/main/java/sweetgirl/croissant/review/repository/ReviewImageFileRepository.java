package sweetgirl.croissant.review.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.review.entity.ReviewImageFile;

public interface ReviewImageFileRepository extends JpaRepository<ReviewImageFile, Long> {

	List<ReviewImageFile> findByReviewIdInOrderByReviewIdAscDisplayOrderAscIdAsc(List<Long> reviewIds);

	List<ReviewImageFile> findByReviewIdOrderByDisplayOrderAscIdAsc(Long reviewId);

	@Query(value = "SELECT * FROM review_image_file WHERE aws_key = :awsKey", nativeQuery = true)
	Optional<ReviewImageFile> findByAwsKey(@Param("awsKey") String awsKey);
}

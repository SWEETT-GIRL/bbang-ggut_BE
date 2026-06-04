package sweetgirl.croissant.review.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.review.entity.ReviewImageFile;

public interface ReviewImageFileRepository extends JpaRepository<ReviewImageFile, Long> {

	List<ReviewImageFile> findByReviewIdInOrderByReviewIdAscDisplayOrderAscIdAsc(List<Long> reviewIds);
}

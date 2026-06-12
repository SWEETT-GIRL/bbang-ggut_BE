package sweetgirl.croissant.contribution.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.contribution.entity.StoreContributionReportImageFile;

public interface StoreContributionReportImageFileRepository
		extends JpaRepository<StoreContributionReportImageFile, Long> {

	List<StoreContributionReportImageFile> findByStoreContributionReportIdIn(List<Long> reportIds);

	@Query(value = "SELECT * FROM store_contribution_report_image_file WHERE aws_key = :awsKey", nativeQuery = true)
	Optional<StoreContributionReportImageFile> findByAwsKey(@Param("awsKey") String awsKey);
}

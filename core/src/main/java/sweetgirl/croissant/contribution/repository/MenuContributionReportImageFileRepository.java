package sweetgirl.croissant.contribution.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.contribution.entity.MenuContributionReportImageFile;

public interface MenuContributionReportImageFileRepository
		extends JpaRepository<MenuContributionReportImageFile, Long> {

	List<MenuContributionReportImageFile> findByMenuContributionReportIdIn(List<Long> reportIds);

	@Query(value = "SELECT * FROM menu_contribution_report_image_file WHERE aws_key = :awsKey", nativeQuery = true)
	Optional<MenuContributionReportImageFile> findByAwsKey(@Param("awsKey") String awsKey);
}

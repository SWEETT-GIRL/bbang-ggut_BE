package sweetgirl.croissant.menu.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.menu.entity.MenuAddReportImageFile;

public interface MenuAddReportImageFileRepository extends JpaRepository<MenuAddReportImageFile, Long> {

	List<MenuAddReportImageFile> findByMenuAddReportIdIn(List<Long> reportIds);

	@Query(value = "SELECT * FROM menu_add_report_image_file WHERE aws_key = :awsKey", nativeQuery = true)
	Optional<MenuAddReportImageFile> findByAwsKey(@Param("awsKey") String awsKey);
}

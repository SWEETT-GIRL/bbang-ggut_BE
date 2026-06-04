package sweetgirl.croissant.store.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.store.entity.StoreReportImageFile;

public interface StoreReportImageFileRepository extends JpaRepository<StoreReportImageFile, Long> {

	List<StoreReportImageFile> findByStoreReportIdIn(List<Long> reportIds);

	@Query(value = "SELECT * FROM store_report_image_file WHERE aws_key = :awsKey", nativeQuery = true)
	Optional<StoreReportImageFile> findByAwsKey(@Param("awsKey") String awsKey);
}

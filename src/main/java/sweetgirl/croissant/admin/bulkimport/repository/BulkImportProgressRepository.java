package sweetgirl.croissant.admin.bulkimport.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.admin.bulkimport.entity.BulkImportProgress;
import sweetgirl.croissant.admin.bulkimport.enums.BulkImportStatus;

public interface BulkImportProgressRepository extends JpaRepository<BulkImportProgress, Long> {

	Optional<BulkImportProgress> findByRegion1stAndRegion2ndAndRegion3rd(
			String region1st, String region2nd, String region3rd);

	List<BulkImportProgress> findByStatusIn(List<BulkImportStatus> statuses);

	long countByStatus(BulkImportStatus status);

	List<BulkImportProgress> findByStatusOrderByLastAttemptedAtDesc(BulkImportStatus status);

	@Modifying
	@Query("UPDATE BulkImportProgress p SET p.status = :newStatus WHERE p.status = :oldStatus")
	int bulkUpdateStatus(@Param("oldStatus") BulkImportStatus oldStatus,
			@Param("newStatus") BulkImportStatus newStatus);
}

package sweetgirl.croissant.breadlog.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.breadlog.entity.BreadRecordPhoto;

public interface BreadRecordPhotoRepository extends JpaRepository<BreadRecordPhoto, Long> {

	List<BreadRecordPhoto> findByRecordIdInOrderByRecordIdAscSortOrderAscIdAsc(List<Long> recordIds);

	@Query(value = """
			SELECT *
			FROM bread_record_photo
			WHERE record_id = :recordId
			ORDER BY sort_order, id
			""", nativeQuery = true)
	List<BreadRecordPhoto> findAllIncludingPendingByRecordId(@Param("recordId") Long recordId);

	@Query(value = "SELECT * FROM bread_record_photo WHERE aws_key = :awsKey", nativeQuery = true)
	Optional<BreadRecordPhoto> findByAwsKey(@Param("awsKey") String awsKey);

	@Modifying
	@Query(value = "DELETE FROM bread_record_photo WHERE record_id = :recordId", nativeQuery = true)
	void deleteAllIncludingPendingByRecordId(@Param("recordId") Long recordId);
}

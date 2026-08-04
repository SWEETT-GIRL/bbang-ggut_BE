package sweetgirl.croissant.breadlog.repository;

import java.util.List;

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

	/**
	 * 같은 awsKey의 사진을 모두 찾는다 — 단건이 아니라 목록인 게 핵심이다.
	 * 한 키에 row가 2개 이상인 데이터가 실제로 존재했고, 단건 조회는 그 순간
	 * IncorrectResultSize로 죽어서 콜백 전체가 유실됐다.
	 */
	@Query(value = "SELECT * FROM bread_record_photo WHERE aws_key = :awsKey ORDER BY id", nativeQuery = true)
	List<BreadRecordPhoto> findAllByAwsKey(@Param("awsKey") String awsKey);

	@Modifying
	@Query(value = "DELETE FROM bread_record_photo WHERE record_id = :recordId", nativeQuery = true)
	void deleteAllIncludingPendingByRecordId(@Param("recordId") Long recordId);
}

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

	/** 이 파일을 가리키는 사진 행이 (처리 상태와 무관하게) 하나라도 있는지. */
	boolean existsByAwsKey(String awsKey);

	/**
	 * 같은 awsKey의 사진을 모두 찾는다 — 단건이 아니라 목록인 게 핵심이다.
	 * 한 키에 row가 2개 이상인 데이터가 실제로 존재했고, 단건 조회는 그 순간
	 * IncorrectResultSize로 죽어서 콜백 전체가 유실됐다.
	 */
	@Query(value = "SELECT * FROM bread_record_photo WHERE aws_key = :awsKey ORDER BY id", nativeQuery = true)
	List<BreadRecordPhoto> findAllByAwsKey(@Param("awsKey") String awsKey);

	/**
	 * 처리 완료를 같은 awsKey의 사진 전부에 한 문장으로 반영하고 반영한 행 수를 돌려준다.
	 * 읽어 둔 엔티티를 고치면, 그 사이 전체 교체가 행을 지웠을 때 UPDATE가 0행을 맞아 예외로 끝난다.
	 * GIF는 리사이즈본이 만들어지지 않으므로 키를 채우지 않는다.
	 */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			UPDATE bread_record_photo
			SET processing_status = 'DONE',
				resized_key_320 = CASE WHEN mime_type = 'IMAGE_GIF' THEN resized_key_320 ELSE :resizedKey320 END,
				resized_key_640 = CASE WHEN mime_type = 'IMAGE_GIF' THEN resized_key_640 ELSE :resizedKey640 END,
				resized_key_1024 = CASE WHEN mime_type = 'IMAGE_GIF' THEN resized_key_1024 ELSE :resizedKey1024 END,
				updated_at = CURRENT_TIMESTAMP
			WHERE aws_key = :awsKey
			""", nativeQuery = true)
	int markDoneByAwsKey(
			@Param("awsKey") String awsKey,
			@Param("resizedKey320") String resizedKey320,
			@Param("resizedKey640") String resizedKey640,
			@Param("resizedKey1024") String resizedKey1024);

	/** 처리 실패를 같은 awsKey의 사진 전부에 반영하고 반영한 행 수를 돌려준다. */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			UPDATE bread_record_photo
			SET processing_status = 'FAILED',
				resized_key_320 = NULL,
				resized_key_640 = NULL,
				resized_key_1024 = NULL,
				updated_at = CURRENT_TIMESTAMP
			WHERE aws_key = :awsKey
			""", nativeQuery = true)
	int markFailedByAwsKey(@Param("awsKey") String awsKey);

	@Modifying
	@Query(value = "DELETE FROM bread_record_photo WHERE record_id = :recordId", nativeQuery = true)
	void deleteAllIncludingPendingByRecordId(@Param("recordId") Long recordId);
}

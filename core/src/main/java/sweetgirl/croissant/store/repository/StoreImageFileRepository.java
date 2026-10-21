package sweetgirl.croissant.store.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import sweetgirl.croissant.common.enums.ImageStatus;
import sweetgirl.croissant.store.dto.projection.StoreImageThumbnailProjection;
import sweetgirl.croissant.store.entity.StoreImageFile;

public interface StoreImageFileRepository extends JpaRepository<StoreImageFile, Long> {

	List<StoreImageFile> findByStoreIdOrderByDisplayOrderAscIdAsc(Long storeId);

	List<StoreImageFile> findByStoreIdIn(List<Long> storeIds);

	List<StoreImageFile> findByIdInAndStoreId(List<Long> ids, Long storeId);

	@Query(value = "SELECT * FROM store_image_file WHERE aws_key = :awsKey", nativeQuery = true)
	Optional<StoreImageFile> findByAwsKey(@Param("awsKey") String awsKey);

	@Query("""
			SELECT s.store.id AS storeId, s.awsKey AS awsKey, s.resizedKey320 AS resizedKey320
			FROM StoreImageFile s
			WHERE s.store.id IN :storeIds
			ORDER BY s.displayOrder ASC, s.id ASC
			""")
	List<StoreImageThumbnailProjection> findThumbnailsByStoreIdIn(@Param("storeIds") List<Long> storeIds);

	/**
	 * 가게마다 첫 사진(displayOrder → id 순) 한 장만 읽는다 — 목록 썸네일은 첫 장만 쓰는데 위 쿼리는 가게의 사진을 전부 읽는다.
	 * 처리 중(PENDING)·실패 사진은 엔티티의 {@code @SQLRestriction} 으로 위 쿼리와 똑같이 빠진다.
	 */
	@Query("""
			SELECT ranked.storeId AS storeId, ranked.awsKey AS awsKey, ranked.resizedKey320 AS resizedKey320
			FROM (
				SELECT s.store.id AS storeId, s.awsKey AS awsKey, s.resizedKey320 AS resizedKey320,
					row_number() OVER (PARTITION BY s.store.id ORDER BY s.displayOrder ASC, s.id ASC) AS rowNumber
				FROM StoreImageFile s
				WHERE s.store.id IN :storeIds
			) ranked
			WHERE ranked.rowNumber = 1
			""")
	List<StoreImageThumbnailProjection> findFirstThumbnailsByStoreIdIn(@Param("storeIds") List<Long> storeIds);

	long countByIdInAndStoreId(List<Long> ids, Long storeId);

	@Query("""
				SELECT s.store.id AS storeId, COUNT(s) AS cnt
				FROM StoreImageFile s
				WHERE s.store.id IN :storeIds
				GROUP BY s.store.id
			""")
	List<Object[]> countByStoreIdIn(@Param("storeIds") List<Long> storeIds);

	@Query("SELECT COALESCE(MAX(s.displayOrder), 0) FROM StoreImageFile s WHERE s.store.id = :storeId")
	int findMaxDisplayOrderByStoreId(@Param("storeId") Long storeId);

	@Query("SELECT s FROM StoreImageFile s WHERE s.store.id = :storeId AND s.sourceUrl = :sourceUrl")
	java.util.Optional<StoreImageFile> findByStoreIdAndSourceUrl(
			@Param("storeId") Long storeId, @Param("sourceUrl") String sourceUrl);

	@Query("SELECT s.awsKey FROM StoreImageFile s")
	List<String> findAllAwsKeys();

	// S3 정리가 트랜잭션 없이 부른다 — 목록 조회 동안 커넥션을 쥐지 않고 묶음마다 짧게 커밋한다.
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE StoreImageFile s
			SET s.processingStatus = :failed
			WHERE s.awsKey IN :awsKeys
			AND s.processingStatus = :current
			""")
	int updateStatusByAwsKeyInAndStatus(
			@Param("awsKeys") List<String> awsKeys,
			@Param("current") ImageStatus current,
			@Param("failed") ImageStatus failed);
}

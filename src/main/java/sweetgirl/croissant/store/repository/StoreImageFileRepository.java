package sweetgirl.croissant.store.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.store.dto.projection.StoreImageThumbnailProjection;
import sweetgirl.croissant.store.entity.StoreImageFile;

public interface StoreImageFileRepository extends JpaRepository<StoreImageFile, Long> {

	List<StoreImageFile> findByStoreIdOrderByDisplayOrderAscIdAsc(Long storeId);

	List<StoreImageFile> findByStoreIdIn(List<Long> storeIds);

	List<StoreImageFile> findByIdInAndStoreId(List<Long> ids, Long storeId);

	@Query(value = "SELECT * FROM store_image_file WHERE aws_key = :awsKey", nativeQuery = true)
	Optional<StoreImageFile> findByAwsKey(@Param("awsKey") String awsKey);

	@Query("""
			SELECT s.store.id AS storeId, s.awsKey AS awsKey
			FROM StoreImageFile s
			WHERE s.store.id IN :storeIds
			ORDER BY s.displayOrder ASC, s.id ASC
			""")
	List<StoreImageThumbnailProjection> findThumbnailsByStoreIdIn(@Param("storeIds") List<Long> storeIds);

	long countByIdInAndStoreId(List<Long> ids, Long storeId);

}

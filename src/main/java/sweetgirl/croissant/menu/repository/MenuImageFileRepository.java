package sweetgirl.croissant.menu.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.menu.dto.projection.MenuImageThumbnailProjection;
import sweetgirl.croissant.menu.entity.MenuImageFile;

public interface MenuImageFileRepository extends JpaRepository<MenuImageFile, Long> {

	long countByIdInAndMenuId(List<Long> ids, Long menuId);

	List<MenuImageFile> findByIdInAndMenuId(List<Long> ids, Long menuId);

	@Query(value = "SELECT * FROM menu_image_file WHERE aws_key = :awsKey", nativeQuery = true)
	Optional<MenuImageFile> findByAwsKey(@Param("awsKey") String awsKey);

	@Query("""
			SELECT m.id AS imageId, m.menu.id AS menuId, m.awsKey AS awsKey
			FROM MenuImageFile m WHERE m.menu.id IN :menuIds
			ORDER BY m.displayOrder ASC, m.id ASC
			""")
	List<MenuImageThumbnailProjection> findThumbnailsByMenuIdIn(@Param("menuIds") List<Long> menuIds);

	@Query("""
			SELECT m FROM MenuImageFile m WHERE m.menu.id = :menuId
			ORDER BY m.displayOrder ASC, m.id ASC
			""")
	List<MenuImageFile> findImagesByMenuId(@Param("menuId") Long menuId);

	List<MenuImageFile> findByMenuStoreIdOrderByDisplayOrderAscIdAsc(Long storeId);

	List<MenuImageFile> findByMenuStoreId(Long storeId);

	@Query("SELECT COALESCE(MAX(m.displayOrder), 0) FROM MenuImageFile m WHERE m.menu.id = :menuId")
	int findMaxDisplayOrderByMenuId(@Param("menuId") Long menuId);

	@Query("SELECT m FROM MenuImageFile m WHERE m.menu.id = :menuId AND m.sourceUrl = :sourceUrl")
	java.util.Optional<MenuImageFile> findByMenuIdAndSourceUrl(
			@Param("menuId") Long menuId, @Param("sourceUrl") String sourceUrl);

	@Query("SELECT m.awsKey FROM MenuImageFile m")
	List<String> findAllAwsKeys();
}

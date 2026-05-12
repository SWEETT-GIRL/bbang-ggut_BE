package sweetgirl.croissant.menu.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.menu.dto.projection.MenuImageThumbnailProjection;
import sweetgirl.croissant.menu.entity.MenuImageFile;

public interface MenuImageFileRepository extends JpaRepository<MenuImageFile, Long> {

	@Query("""
			SELECT m.id AS imageId, m.menu.id AS menuId, m.awsKey AS awsKey
			FROM MenuImageFile m WHERE m.menu.id IN :menuIds ORDER BY m.id DESC
			""")
	List<MenuImageThumbnailProjection> findThumbnailsByMenuIdIn(@Param("menuIds") List<Long> menuIds);

	List<MenuImageFile> findByMenuStoreId(Long storeId);
}

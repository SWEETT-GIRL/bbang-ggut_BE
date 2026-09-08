package sweetgirl.croissant.menu.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.common.enums.ImageStatus;
import sweetgirl.croissant.menu.dto.projection.MenuImageByStoreProjection;
import sweetgirl.croissant.menu.dto.projection.MenuImageThumbnailProjection;
import sweetgirl.croissant.menu.entity.MenuImageFile;

public interface MenuImageFileRepository extends JpaRepository<MenuImageFile, Long> {

	long countByIdInAndMenuId(List<Long> ids, Long menuId);

	List<MenuImageFile> findByIdInAndMenuId(List<Long> ids, Long menuId);

	@Query(value = "SELECT * FROM menu_image_file WHERE aws_key = :awsKey", nativeQuery = true)
	Optional<MenuImageFile> findByAwsKey(@Param("awsKey") String awsKey);

	@Query("""
			SELECT m.id AS imageId, m.menu.id AS menuId, m.awsKey AS awsKey,
			m.resizedKey320 AS resizedKey320, m.resizedKey640 AS resizedKey640,
			m.resizedKey1024 AS resizedKey1024
			FROM MenuImageFile m WHERE m.menu.id IN :menuIds
			ORDER BY m.displayOrder ASC, m.id ASC
			""")
	List<MenuImageThumbnailProjection> findThumbnailsByMenuIdIn(@Param("menuIds") List<Long> menuIds);

	@Query("""
			SELECT m FROM MenuImageFile m WHERE m.menu.id = :menuId
			ORDER BY m.displayOrder ASC, m.id ASC
			""")
	List<MenuImageFile> findImagesByMenuId(@Param("menuId") Long menuId);

	// 트렌드 썸네일용 — 가게들의 메뉴 이미지를 세분류(MenuKind)와 함께 일괄 조회 (N+1 방지)
	@Query("""
			SELECT mn.store.id AS storeId, mc.name AS menuKind,
			m.resizedKey320 AS resizedKey320, m.awsKey AS awsKey
			FROM MenuImageFile m
			JOIN m.menu mn
			JOIN mn.menuCategory mc
			WHERE mn.store.id IN :storeIds AND mn.deletedAt IS NULL
			ORDER BY m.displayOrder ASC, m.id ASC
			""")
	List<MenuImageByStoreProjection> findMenuImagesByStoreIdIn(@Param("storeIds") List<Long> storeIds);

	/**
	 * 가게 상세의 메뉴 사진 갤러리. **삭제된 메뉴의 사진은 빼야 한다** — 빼지 않으면 갤러리에
	 * 지운 메뉴의 사진이 남고, 그 사진으로 세는 메뉴 수도 메뉴 목록 화면과 어긋난다.
	 * (버터웨이브: 사진 8장 중 4장이 지워진 메뉴 것이라 상세는 '메뉴 8', 목록은 5개였다)
	 */
	List<MenuImageFile> findByMenuStoreIdAndMenuDeletedAtIsNullOrderByDisplayOrderAscIdAsc(Long storeId);

	@Query("SELECT COALESCE(MAX(m.displayOrder), 0) FROM MenuImageFile m WHERE m.menu.id = :menuId")
	int findMaxDisplayOrderByMenuId(@Param("menuId") Long menuId);

	@Query("SELECT m FROM MenuImageFile m WHERE m.menu.id = :menuId AND m.sourceUrl = :sourceUrl")
	java.util.Optional<MenuImageFile> findByMenuIdAndSourceUrl(
			@Param("menuId") Long menuId, @Param("sourceUrl") String sourceUrl);

	@Query("SELECT m.awsKey FROM MenuImageFile m")
	List<String> findAllAwsKeys();

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE MenuImageFile m
			SET m.processingStatus = :failed
			WHERE m.awsKey IN :awsKeys
			AND m.processingStatus = :current
			""")
	int updateStatusByAwsKeyInAndStatus(
			@Param("awsKeys") List<String> awsKeys,
			@Param("current") ImageStatus current,
			@Param("failed") ImageStatus failed);
}

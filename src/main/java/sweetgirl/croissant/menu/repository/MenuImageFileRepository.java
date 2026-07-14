package sweetgirl.croissant.menu.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.common.enums.ImageStatus;
import sweetgirl.croissant.menu.dto.projection.MenuImageThumbnailProjection;
import sweetgirl.croissant.menu.dto.projection.StoreMenuThumbnailProjection;
import sweetgirl.croissant.menu.entity.MenuImageFile;

public interface MenuImageFileRepository extends JpaRepository<MenuImageFile, Long> {

	long countByIdInAndMenuId(List<Long> ids, Long menuId);

	List<MenuImageFile> findByIdInAndMenuId(List<Long> ids, Long menuId);

	@Query(value = "SELECT * FROM menu_image_file WHERE aws_key = :awsKey", nativeQuery = true)
	Optional<MenuImageFile> findByAwsKey(@Param("awsKey") String awsKey);

	@Query("""
			SELECT m.id AS imageId,
			m.menu.id AS menuId,
			m.awsKey AS awsKey,
			m.resizedKey320 AS resizedKey320,
			m.resizedKey640 AS resizedKey640,
			m.resizedKey1024 AS resizedKey1024
			FROM MenuImageFile m
			WHERE m.menu.id IN :menuIds
			ORDER BY m.displayOrder ASC, m.id ASC
			""")
	List<MenuImageThumbnailProjection> findThumbnailsByMenuIdIn(@Param("menuIds") List<Long> menuIds);

	@Query("""
			SELECT m FROM MenuImageFile m WHERE m.menu.id = :menuId
			ORDER BY m.displayOrder ASC, m.id ASC
			""")
	List<MenuImageFile> findImagesByMenuId(@Param("menuId") Long menuId);

	// 트렌드 랭킹용: 여러 가게의 (메뉴 - 메뉴이미지)를 한 번에 조회.
	// 삭제되지 않은 메뉴만, 대표 메뉴/이미지 선택을 위해 메뉴 displayOrder → 이미지 displayOrder 순으로 정렬.
	@Query("""
			SELECT mn.store.id AS storeId, mn.id AS menuId, mn.menuCategory.name AS menuKind,
			mn.displayOrder AS menuDisplayOrder, m.awsKey AS awsKey, m.resizedKey320 AS resizedKey320
			FROM MenuImageFile m JOIN m.menu mn
			WHERE mn.store.id IN :storeIds AND mn.deletedAt IS NULL
			ORDER BY mn.store.id ASC, mn.displayOrder ASC NULLS LAST, mn.id ASC,
			m.displayOrder ASC, m.id ASC
			""")
	List<StoreMenuThumbnailProjection> findStoreMenuThumbnailsByStoreIdIn(
			@Param("storeIds") List<Long> storeIds);

	List<MenuImageFile> findByMenuStoreIdOrderByDisplayOrderAscIdAsc(Long storeId);

	List<MenuImageFile> findByMenuStoreId(Long storeId);

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

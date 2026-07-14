package sweetgirl.croissant.menu.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.menu.dto.projection.StoreMenuThumbnailProjection;
import sweetgirl.croissant.menu.enums.MenuKind;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:menu-img-repo-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class MenuImageFileRepositoryTest {

	@MockitoBean
	private S3Service s3Service;

	@Autowired
	private MenuImageFileRepository menuImageFileRepository;

	@Autowired
	private EntityManager entityManager;

	private static final Long STORE_ID = 10L;

	@BeforeEach
	void setUpSchema() {
		execute("DROP TABLE IF EXISTS menu_image_file CASCADE");
		execute("DROP TABLE IF EXISTS menu CASCADE");
		execute("DROP TABLE IF EXISTS menu_category CASCADE");
		execute("""
				CREATE TABLE menu_category (
					id BIGINT PRIMARY KEY,
					name VARCHAR(50) NOT NULL
				)
				""");
		execute("""
				CREATE TABLE menu (
					id BIGINT PRIMARY KEY,
					store_id BIGINT NOT NULL,
					menu_category_id BIGINT NOT NULL,
					name VARCHAR(100) NOT NULL,
					price INTEGER,
					like_count INTEGER NOT NULL DEFAULT 0,
					deleted_at TIMESTAMP,
					description VARCHAR(255),
					display_order INTEGER,
					created_at TIMESTAMP NOT NULL,
					updated_at TIMESTAMP
				)
				""");
		execute("""
				CREATE TABLE menu_image_file (
					id BIGINT PRIMARY KEY,
					menu_id BIGINT NOT NULL,
					name VARCHAR(255) NOT NULL,
					mime_type VARCHAR(50) NOT NULL,
					aws_key VARCHAR(255) NOT NULL,
					processing_status VARCHAR(20) NOT NULL,
					resized_key_320 VARCHAR(255),
					resized_key_640 VARCHAR(255),
					resized_key_1024 VARCHAR(255),
					display_order INTEGER NOT NULL,
					source_url VARCHAR(500),
					created_at TIMESTAMP NOT NULL,
					updated_at TIMESTAMP
				)
				""");
		saveMenuCategory(1L, MenuKind.소금빵);
		saveMenuCategory(2L, MenuKind.크루아상);
		saveMenuCategory(3L, MenuKind.베이글);
	}

	@Test
	@DisplayName("메뉴 displayOrder → 이미지 displayOrder 순으로 정렬되어 대표 메뉴가 먼저 온다")
	void findStoreMenuThumbnails_orderedByMenuThenImageDisplayOrder() {
		// given: 소금빵 메뉴(order 1)와 크루아상 메뉴(order 2), 소금빵은 이미지 2장(order 2,1)
		saveMenu(100L, 1L, "소금빵", 1);
		saveMenu(200L, 2L, "크루아상", 2);
		saveDoneImage(1001L, 100L, "salt-b", 2);
		saveDoneImage(1000L, 100L, "salt-a", 1);
		saveDoneImage(2000L, 200L, "croissant-a", 1);
		flushAndClear();

		// when
		List<StoreMenuThumbnailProjection> result = menuImageFileRepository
			.findStoreMenuThumbnailsByStoreIdIn(List.of(STORE_ID));

		// then: 소금빵(order1) 이미지들 먼저(order 1,2), 그다음 크루아상
		assertThat(result).extracting(StoreMenuThumbnailProjection::getAwsKey)
			.containsExactly("salt-a", "salt-b", "croissant-a");
		assertThat(result.get(0).getMenuKind()).isEqualTo(MenuKind.소금빵);
		assertThat(result.get(0).getMenuId()).isEqualTo(100L);
	}

	@Test
	@DisplayName("soft-delete된 메뉴의 이미지와 처리 미완료(PENDING) 이미지는 제외된다")
	void findStoreMenuThumbnails_excludesDeletedMenuAndPendingImage() {
		// given
		saveMenu(100L, 1L, "소금빵", 1);
		saveDoneImage(1000L, 100L, "keep", 1);
		savePendingImage(1001L, 100L, "pending", 2);
		saveDeletedMenu(300L, 3L, "베이글", 2);
		saveDoneImage(3000L, 300L, "deleted-menu", 1);
		flushAndClear();

		// when
		List<StoreMenuThumbnailProjection> result = menuImageFileRepository
			.findStoreMenuThumbnailsByStoreIdIn(List.of(STORE_ID));

		// then: DONE 상태 & 삭제되지 않은 메뉴의 이미지만
		assertThat(result).extracting(StoreMenuThumbnailProjection::getAwsKey)
			.containsExactly("keep");
	}

	private void saveMenuCategory(Long id, MenuKind name) {
		execute("INSERT INTO menu_category (id, name) VALUES (?, ?)", id, name.name());
	}

	private void saveMenu(Long id, Long categoryId, String name, Integer displayOrder) {
		execute("""
				INSERT INTO menu (id, store_id, menu_category_id, name, display_order, created_at)
				VALUES (?, ?, ?, ?, ?, TIMESTAMP '2026-06-05 08:00:00')
				""", id, STORE_ID, categoryId, name, displayOrder);
	}

	private void saveDeletedMenu(Long id, Long categoryId, String name, Integer displayOrder) {
		execute("""
				INSERT INTO menu (id, store_id, menu_category_id, name, display_order, deleted_at, created_at)
				VALUES (?, ?, ?, ?, ?, TIMESTAMP '2026-06-05 09:00:00', TIMESTAMP '2026-06-05 08:00:00')
				""", id, STORE_ID, categoryId, name, displayOrder);
	}

	private void saveDoneImage(Long id, Long menuId, String awsKey, Integer displayOrder) {
		saveImage(id, menuId, awsKey, displayOrder, "DONE");
	}

	private void savePendingImage(Long id, Long menuId, String awsKey, Integer displayOrder) {
		saveImage(id, menuId, awsKey, displayOrder, "PENDING");
	}

	private void saveImage(Long id, Long menuId, String awsKey, Integer displayOrder, String status) {
		execute("""
				INSERT INTO menu_image_file (
					id, menu_id, name, mime_type, aws_key, processing_status,
					resized_key_320, display_order, created_at
				)
				VALUES (?, ?, ?, 'IMAGE_JPEG', ?, ?, ?, ?, TIMESTAMP '2026-06-05 08:00:00')
				""", id, menuId, awsKey, awsKey, status, awsKey + "-320", displayOrder);
	}

	private void flushAndClear() {
		entityManager.flush();
		entityManager.clear();
	}

	private void execute(String sql, Object... parameters) {
		var query = entityManager.createNativeQuery(sql);
		for (int i = 0; i < parameters.length; i++) {
			query.setParameter(i + 1, parameters[i]);
		}
		query.executeUpdate();
	}
}

package sweetgirl.croissant.external.aws.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import sweetgirl.croissant.external.aws.entity.ImageProcessingCallback;

/**
 * 리사이즈 결과를 기다리는 이미지 수를 센다.
 *
 * <p>이미지 엔티티에는 {@code @SQLRestriction("processing_status = 'DONE'")}이 걸려 있어
 * JPA 파생 쿼리로 {@code PENDING}을 세면 조건이 덧붙어 항상 0이 나온다. 네이티브 쿼리가 필요하다.
 *
 * <p>테이블이 여러 개지만 UNION ALL로 한 번에 묶어 왕복을 1회로 유지한다
 * (프로덕션 33만 행 기준 실행계획 측정 시 약 50ms).
 */
public interface ImagePendingCountRepository extends JpaRepository<ImageProcessingCallback, Long> {

	@Query(value = """
			SELECT COUNT(*) FROM (
				SELECT 1 FROM store_image_file WHERE processing_status = 'PENDING'
				UNION ALL
				SELECT 1 FROM menu_image_file WHERE processing_status = 'PENDING'
				UNION ALL
				SELECT 1 FROM review_image_file WHERE processing_status = 'PENDING'
				UNION ALL
				SELECT 1 FROM course_image_files WHERE processing_status = 'PENDING'
				UNION ALL
				SELECT 1 FROM store_contribution_report_image_file WHERE processing_status = 'PENDING'
				UNION ALL
				SELECT 1 FROM menu_contribution_report_image_file WHERE processing_status = 'PENDING'
				UNION ALL
				SELECT 1 FROM bread_record_photo WHERE processing_status = 'PENDING'
				UNION ALL
				SELECT 1 FROM banner WHERE processing_status = 'PENDING'
			) pending
			""", nativeQuery = true)
	long countPendingImages();
}

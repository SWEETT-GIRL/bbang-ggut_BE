package sweetgirl.croissant.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.course.entity.CourseStoreVisit;
import sweetgirl.croissant.course.entity.CourseStoreVisitId;

public interface CourseStoreVisitRepository extends JpaRepository<CourseStoreVisit, CourseStoreVisitId> {

	// 소프트 삭제된 행도 찾아야 한다 — 체크를 껐다가 다시 켜면 같은 키로 되살린다.
	Optional<CourseStoreVisit> findByIdCourseIdAndIdMemberIdAndIdStoreId(
			Long courseId, Long memberId, Long storeId);

	List<CourseStoreVisit> findAllByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(Long courseId, Long memberId);

	List<CourseStoreVisit> findAllByIdMemberIdAndDeletedAtIsNull(Long memberId);

	// 기록을 지우면 그 기록으로 켜둔 체크도 함께 정리한다.
	List<CourseStoreVisit> findAllByBreadRecordIdAndDeletedAtIsNull(Long breadRecordId);
}

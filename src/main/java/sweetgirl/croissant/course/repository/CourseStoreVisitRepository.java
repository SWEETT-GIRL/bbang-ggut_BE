package sweetgirl.croissant.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

	/** 게스트 → 회원 병합(#663 ④). PK 가 (course_id, member_id, store_id) 라 회원이 이미 찍은 방문은 게스트 쪽을 먼저 지운다. */
	@Modifying(clearAutomatically = true)
	@Query("DELETE FROM CourseStoreVisit visit WHERE visit.member.id = :guestId AND EXISTS "
		+ "(SELECT 1 FROM CourseStoreVisit other WHERE other.member.id = :targetId "
		+ "AND other.course.id = visit.course.id AND other.store.id = visit.store.id)")
	int deleteGuestDuplicates(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

	/** 방문 체크는 사용자 유형(DOER)의 재료다 — 코스가 회원에게 가면 방문도 따라가야 한다. */
	@Modifying(clearAutomatically = true)
	@Query("UPDATE CourseStoreVisit visit SET visit.member.id = :targetId WHERE visit.member.id = :guestId")
	int moveOwner(@Param("guestId") Long guestId, @Param("targetId") Long targetId);
}

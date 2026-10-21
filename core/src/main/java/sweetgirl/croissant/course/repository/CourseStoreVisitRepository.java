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

	/**
	 * 이 빵투어의 가게 가운데, 이 회원이 사진 붙은 기록으로 다녀오지 않은 가게 수.
	 * 0 이면 모든 가게에 사진 붙은 기록이 달린 것이다(가게가 하나도 없는 빵투어는 1 로 답해 완주로 보지 않는다).
	 */
	@Query(value = """
			SELECT CASE WHEN COUNT(*) = 0 THEN 1 ELSE COUNT(*) FILTER (WHERE NOT EXISTS (
				SELECT 1 FROM course_store_visits visit
				JOIN bread_record_photo photo ON photo.record_id = visit.bread_record_id
				WHERE visit.course_id = course_store.course_id
					AND visit.store_id = course_store.store_id
					AND visit.member_id = :memberId
					AND visit.deleted_at IS NULL)) END
			FROM course_stores course_store
			WHERE course_store.course_id = :courseId AND course_store.deleted_at IS NULL
			""", nativeQuery = true)
	long countStoresWithoutPhotoVisit(@Param("courseId") Long courseId, @Param("memberId") Long memberId);

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

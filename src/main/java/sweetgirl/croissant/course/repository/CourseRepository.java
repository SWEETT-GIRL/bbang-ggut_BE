package sweetgirl.croissant.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.enums.CourseType;

public interface CourseRepository extends JpaRepository<Course, Long> {

	/**
	 * 공개 목록에 세울 코스. 가게가 2곳 미만이면 뺀다.
	 *
	 * 만들다 만 코스가 목록의 절반이었다 — 사용자가 만든 공개 코스 15개 중 8개가 가게 0~1곳,
	 * 그중 7개는 아예 0곳이었다. 만들자마자 공개가 기본값이 된 뒤로는 이것들이 그대로 쌓인다.
	 * 빈 코스는 아무도 찜하지 않는다(가게 2곳 미만인 코스의 찜은 전부 0이었다).
	 * 목록에서만 뺄 뿐 코스 자체는 살아 있어서, 주인은 내 코스에서 계속 보고 링크로도 열린다.
	 */
	@Query("""
			SELECT course
			FROM Course course
			LEFT JOIN SavedCourse savedCourse ON savedCourse.course = course
			WHERE course.isPublic = true
			AND course.deletedAt IS NULL
			AND (
				SELECT COUNT(courseStore)
				FROM CourseStore courseStore
				WHERE courseStore.course = course
				AND courseStore.deletedAt IS NULL
			) >= 2
			GROUP BY course
			ORDER BY
				CASE WHEN course.pinOrder IS NULL THEN 1 ELSE 0 END ASC,
				course.pinOrder ASC,
				COUNT(savedCourse.id) DESC,
				course.id DESC
			""")
	List<Course> findAllPublicOrderedByPinAndSavedCount();

	List<Course> findAllByMemberIdAndTypeInAndDeletedAtIsNull(Long memberId, List<CourseType> types);

	long countByMemberIdAndTypeInAndDeletedAtIsNull(Long memberId, List<CourseType> types);

	Optional<Course> findByIdAndDeletedAtIsNull(Long id);

	/**
	 * 코스 행에 비관적 락을 걸고 읽는다. 같은 코스에 동시에 들어오는 쓰기를 줄 세운다 —
	 * 가게 추가·삭제·순서 변경(유니크 순서 충돌로 500), 초대 생성(활성 링크 2개), 수락(정원 9명).
	 * 전 조회에 락을 걸면 성능이 죽으므로 위 다섯 곳만 쓴다. 일반 조회는 findByIdAndDeletedAtIsNull 그대로.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Course> findWithLockByIdAndDeletedAtIsNull(Long id);

	List<Course> findAllByTypeAndDeletedAtIsNullOrderByCreatedAtDesc(CourseType type);

	long countByPinOrderIsNotNullAndDeletedAtIsNull();

	Optional<Course> findByPinOrderAndDeletedAtIsNull(Integer pinOrder);

	// ── 게스트 → 회원 데이터 이관 ─────────────────────────────
	// 코스는 유니크 제약이 없어 그대로 주인만 바꾼다.
	@Modifying(clearAutomatically = true)
	@Query("UPDATE Course c SET c.member.id = :targetId WHERE c.member.id = :guestId")
	int moveOwner(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

}

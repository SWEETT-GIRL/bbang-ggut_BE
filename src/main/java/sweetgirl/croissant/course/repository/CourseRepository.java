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

	/** 링크 공개 입구 — 랜딩이 /c/{shareToken} 으로 받으면 코스 id 를 모른다. */
	Optional<Course> findByShareTokenAndDeletedAtIsNull(String shareToken);

	/**
	 * 코스 행에 비관적 락을 걸고 읽는다. 같은 코스에 동시에 들어오는 쓰기를 줄 세운다 —
	 * 가게 추가·삭제·순서 변경(유니크 순서 충돌로 500), 코스·메모 수정, 초대 생성(활성 링크 2개), 수락(정원 9명),
	 * 나가기·탈퇴 정리(승계). 코스나 그 자식 행을 바꾸는 쓰기는 전부 이걸로 시작해 락 순서를 courses → 자식으로 맞춘다.
	 * 조회에는 걸지 않는다 — findByIdAndDeletedAtIsNull 그대로.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Course> findWithLockByIdAndDeletedAtIsNull(Long id);

	/**
	 * 폴링 ETag 올리기. 코스·가게·메모·멤버를 바꾸는 모든 쓰기가 마지막에 부른다 — 빠뜨리면 폴링이 그 변경을 영영 못 본다.
	 * SET version = version + 1 은 행 단위로 원자적이라 락 없는 경로(제목·메모·이름 변경)에서도 증가가 유실되지 않는다.
	 * Course.version 은 JPA 쪽에서 읽기 전용이라 네이티브로 쓴다.
	 * updated_at 도 같이 올린다 — 가게·메모·멤버 변경은 courses 행을 안 건드려서, 이게 없으면
	 * 내 목록의 '최근 수정순'이 협업 편집을 못 본다.
	 */
	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE courses
			SET version = version + 1, updated_at = CURRENT_TIMESTAMP
			WHERE id = :courseId
			""", nativeQuery = true)
	int bumpVersion(@Param("courseId") Long courseId);

	List<Course> findAllByTypeAndDeletedAtIsNullOrderByCreatedAtDesc(CourseType type);

	long countByPinOrderIsNotNullAndDeletedAtIsNull();

	Optional<Course> findByPinOrderAndDeletedAtIsNull(Integer pinOrder);

	// ── 게스트 → 회원 데이터 이관 ─────────────────────────────
	// 코스는 유니크 제약이 없어 그대로 주인만 바꾼다.
	@Modifying(clearAutomatically = true)
	@Query("UPDATE Course c SET c.member.id = :targetId WHERE c.member.id = :guestId")
	int moveOwner(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

}

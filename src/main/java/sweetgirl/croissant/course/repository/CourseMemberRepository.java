package sweetgirl.croissant.course.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.course.entity.CourseMember;
import sweetgirl.croissant.course.entity.CourseMemberId;

public interface CourseMemberRepository extends JpaRepository<CourseMember, CourseMemberId> {

	/** 편집 권한 판정 — 활성 멤버인가. */
	boolean existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(Long courseId, Long memberId);

	/** 협업 판정 — 방장 말고 활성 멤버가 있는가(공개 전환 차단). 참여자 목록을 읽지 않는다. */
	boolean existsByIdCourseIdAndIdMemberIdNotAndDeletedAtIsNull(Long courseId, Long memberId);

	/** 재참여용 — 나간 행(deleted)도 같은 PK 로 되살려야 하므로 포함해서 찾는다. */
	Optional<CourseMember> findByIdCourseIdAndIdMemberId(Long courseId, Long memberId);

	/** 참여자 목록 · 메모 정렬 · 방장 승계 순서. 방장은 첫 초대 때 행이 생기므로 항상 맨 앞이다. */
	List<CourseMember> findAllByIdCourseIdAndDeletedAtIsNullOrderByJoinedAtAsc(Long courseId);

	/** 정원(방장 포함 8명) 판정. */
	long countByIdCourseIdAndDeletedAtIsNull(Long courseId);

	/** 내가 참여한 코스. 코스와 그 방장을 같이 읽는다 — 내 목록이 코스마다 방장 닉네임(작성자)을 내려준다. */
	@EntityGraph(attributePaths = {"course", "course.member"})
	List<CourseMember> findAllByIdMemberIdAndDeletedAtIsNull(Long memberId);

	/** 참여 중인 코스 id 만, 코스 id 오름차순 — 탈퇴 정리가 코스마다 락을 잡을 때 순서를 고정해 데드락을 피한다. */
	@Query("""
			SELECT m.id.courseId
			FROM CourseMember m
			WHERE m.id.memberId = :memberId AND m.deletedAt IS NULL
			ORDER BY m.id.courseId
			""")
	List<Long> findActiveCourseIdsByMemberId(@Param("memberId") Long memberId);

	// ── 게스트 → 회원 데이터 이관 ─────────────────────────────
	// (course_id, member_id) 가 PK 라 회원이 이미 같은 코스의 행을 가지면 게스트 쪽을 버린다.
	@Modifying(clearAutomatically = true)
	@Query(value = """
			DELETE FROM course_members guest
			WHERE guest.member_id = :guestId
			AND EXISTS (
				SELECT 1 FROM course_members target
				WHERE target.member_id = :targetId
				AND target.course_id = guest.course_id
			)
			""", nativeQuery = true)
	int deleteGuestDuplicates(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

	// 식별자 컬럼을 바꾸는 갱신이라 JPQL 대신 네이티브로 쓴다 (course_progress 와 같은 이유).
	@Modifying(clearAutomatically = true)
	@Query(value = "UPDATE course_members SET member_id = :targetId WHERE member_id = :guestId", nativeQuery = true)
	int moveOwner(@Param("guestId") Long guestId, @Param("targetId") Long targetId);
}

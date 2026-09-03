package sweetgirl.croissant.course.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.course.entity.CourseStoreMemo;
import sweetgirl.croissant.course.entity.CourseStoreMemoId;

public interface CourseStoreMemoRepository extends JpaRepository<CourseStoreMemo, CourseStoreMemoId> {

	/** 내 메모 upsert 용 — 지운 행도 같은 PK 로 되살려야 하므로 포함해서 찾는다. */
	Optional<CourseStoreMemo> findByIdCourseStoreIdAndIdMemberId(Long courseStoreId, Long memberId);

	/**
	 * 상세 한 방 조회. 작성자를 같이 읽는다 — course_members 행이 없는 사람(이관 전 옛 데이터)은
	 * 닉네임으로 폴백해야 하는데, 정류장마다 회원을 따로 읽으면 N+1 이다.
	 * MemberReader.getMember 를 쓰지 않는 이유: 탈퇴자면 예외를 던져 응답 전체가 죽는다.
	 */
	@EntityGraph(attributePaths = "member")
	List<CourseStoreMemo> findAllByIdCourseStoreIdInAndDeletedAtIsNull(Collection<Long> courseStoreIds);

	/** 가게를 코스에서 뺄 때 — 그 정류장의 메모를 전원 닫는다. */
	@Modifying(flushAutomatically = true)
	@Query("""
			UPDATE CourseStoreMemo m
			SET m.deletedAt = CURRENT_TIMESTAMP
			WHERE m.id.courseStoreId = :courseStoreId
			AND m.deletedAt IS NULL
			""")
	int softDeleteAllByCourseStoreId(@Param("courseStoreId") Long courseStoreId);

	/** 나가기·탈퇴 — 그 사람이 이 코스에 쓴 메모를 전부 닫는다. */
	@Modifying(flushAutomatically = true)
	@Query("""
			UPDATE CourseStoreMemo m
			SET m.deletedAt = CURRENT_TIMESTAMP
			WHERE m.id.memberId = :memberId
			AND m.id.courseStoreId IN (SELECT s.id FROM CourseStore s WHERE s.course.id = :courseId)
			AND m.deletedAt IS NULL
			""")
	int softDeleteAllByCourseIdAndMemberId(@Param("courseId") Long courseId, @Param("memberId") Long memberId);

	// ── 게스트 → 회원 데이터 이관 ─────────────────────────────
	// (course_store_id, member_id) 가 PK 라 회원이 이미 같은 정류장에 메모가 있으면 게스트 쪽을 버린다.
	@Modifying(clearAutomatically = true)
	@Query(value = """
			DELETE FROM course_store_memos guest
			WHERE guest.member_id = :guestId
			AND EXISTS (
				SELECT 1 FROM course_store_memos target
				WHERE target.member_id = :targetId
				AND target.course_store_id = guest.course_store_id
			)
			""", nativeQuery = true)
	int deleteGuestDuplicates(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

	// 식별자 컬럼을 바꾸는 갱신이라 네이티브로 쓴다 (course_members 와 같은 이유).
	@Modifying(clearAutomatically = true)
	@Query(value = "UPDATE course_store_memos SET member_id = :targetId WHERE member_id = :guestId", nativeQuery = true)
	int moveOwner(@Param("guestId") Long guestId, @Param("targetId") Long targetId);
}

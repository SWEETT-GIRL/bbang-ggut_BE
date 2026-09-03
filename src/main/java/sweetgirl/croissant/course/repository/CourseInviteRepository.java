package sweetgirl.croissant.course.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.course.entity.CourseInvite;

public interface CourseInviteRepository extends JpaRepository<CourseInvite, Long> {

	/** 무효화된 링크는 제외한다. 만료 여부는 호출부가 expires_at 으로 판정한다. */
	Optional<CourseInvite> findByTokenAndDeletedAtIsNull(String token);

	/** 코스의 살아있는 링크. 서비스가 코스당 1개를 보장하지만 방어적으로 최신 것을 고른다. */
	Optional<CourseInvite> findFirstByCourseIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long courseId);

	// ── 게스트 → 회원 데이터 이관 ─────────────────────────────
	// 토큰은 코스의 것이라 만든 사람만 바꾼다. 유니크 제약이 걸린 컬럼이 아니라 중복 정리가 필요 없다.
	@Modifying(clearAutomatically = true)
	@Query("UPDATE CourseInvite i SET i.createdBy.id = :targetId WHERE i.createdBy.id = :guestId")
	int moveOwner(@Param("guestId") Long guestId, @Param("targetId") Long targetId);
}

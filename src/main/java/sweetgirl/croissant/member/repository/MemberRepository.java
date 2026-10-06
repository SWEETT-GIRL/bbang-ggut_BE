package sweetgirl.croissant.member.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.member.entity.Member;

public interface MemberRepository extends JpaRepository<Member, Long> {

	/** 월말 전에 가입한 현재 일반 회원. 이미 발행한 회원은 재시도 순회에서 제외한다. */
	@Query("""
			SELECT m.id FROM Member m
			WHERE m.id > :afterId AND m.memberType = 'USER' AND m.deletedAt IS NULL
				AND m.createdAt < :until
				AND NOT EXISTS (SELECT r FROM MonthlyReportMember r
					WHERE r.id.memberId = m.id AND r.id.yearMonth = :yearMonth
						AND r.status = sweetgirl.croissant.monthlyreport.enums.MonthlyReportStatus.PUBLISHED)
			ORDER BY m.id
			""")
	Slice<Long> findMonthlyReportTargets(@Param("yearMonth") String yearMonth,
			@Param("until") LocalDateTime until, @Param("afterId") Long afterId, Pageable pageable);

	boolean existsByIdAndMemberTypeAndDeletedAtIsNullAndCreatedAtBefore(Long id, String memberType,
			LocalDateTime until);

	// id로 회원을 조회하되, 탈퇴한 회원(deletedAt이 null이 아닌 경우)은 조회 결과에서 제외
	// 탈퇴 회원에게 서비스 접근을 막기 위해 기존 findById 대신 이 메서드를 사용
	Optional<Member> findByIdAndDeletedAtIsNull(Long id);

	@Query("SELECT m.nickname FROM Member m WHERE m.deletedAt IS NULL")
	List<String> findAllNicknames();

	boolean existsByNicknameAndDeletedAtIsNull(String nickname);

	/**
	 * 광고 보상으로 닉네임 후보를 하나 뽑아 둔다. 이번 주 무료를 이미 썼고 오늘은 아직 안 뽑았을 때만 1 이다.
	 *
	 * <p>구글이 같은 콜백을 나란히 재시도해도 조건을 DB 가 보므로 하루 한 번을 넘지 않는다.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
			UPDATE Member m SET m.pendingNickname = :candidate, m.nicknameDrawnAt = :now
			WHERE m.id = :memberId AND m.deletedAt IS NULL
				AND m.nicknameDrawnAt >= :weekStart AND m.nicknameDrawnAt < :todayStart
			""")
	int drawNicknameWithAdIfAllowed(
			@Param("memberId") Long memberId,
			@Param("candidate") String candidate,
			@Param("now") LocalDateTime now,
			@Param("weekStart") LocalDateTime weekStart,
			@Param("todayStart") LocalDateTime todayStart);

	/**
	 * 어드민이 편지 받을 사람을 고를 때 쓰는 검색. 닉네임 부분일치이고, 검색어가 비면 최근 가입순이다.
	 *
	 * <p>빈 문자열을 넘겨도 되도록 LIKE '%%'로 떨어지게 뒀다 — 널 파라미터를 조건에서 분기하면
	 * 쿼리가 두 갈래가 된다.
	 */
	@Query("""
			SELECT m FROM Member m
			WHERE m.deletedAt IS NULL
				AND LOWER(m.nickname) LIKE LOWER(CONCAT('%', :keyword, '%'))
			ORDER BY m.createdAt DESC
			""")
	Slice<Member> searchForAdmin(@Param("keyword") String keyword, Pageable pageable);
}

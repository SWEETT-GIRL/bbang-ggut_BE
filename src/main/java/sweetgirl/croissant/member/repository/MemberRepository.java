package sweetgirl.croissant.member.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import sweetgirl.croissant.member.entity.Member;

public interface MemberRepository extends JpaRepository<Member, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT m FROM Member m WHERE m.id = :memberId AND m.deletedAt IS NULL")
	Optional<Member> findActiveForUpdate(@Param("memberId") Long memberId);

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

	Optional<Member> findByNicknameAndDeletedAtIsNull(String nickname);

	/** 살아 있는 회원 중 같은 이름(정규화)이 있나 — 직접 바꾸기 · 뽑기 수락이 본다. */
	boolean existsByNicknameNormalizedAndDeletedAtIsNull(String nicknameNormalized);

	/**
	 * 빵순이 찾기 — 정규화 닉네임 앞글자 일치. 일반 회원 · 탈퇴 아님 · 보는 사람과 차단 관계 아님 · 프로필 제한 아님.
	 * 보는 사람이 없으면(비로그인) 차단 조건은 아무도 안 거른다.
	 */
	@Query("""
			SELECT m FROM Member m
			WHERE m.deletedAt IS NULL AND m.memberType = 'USER'
				AND m.nicknameNormalized LIKE CONCAT(:prefix, '%')
				AND NOT EXISTS (SELECT 1 FROM MemberBlock b
					WHERE (b.blocker.id = :viewerId AND b.blockedMember.id = m.id)
						OR (b.blocker.id = m.id AND b.blockedMember.id = :viewerId))
				AND NOT EXISTS (SELECT 1 FROM MemberRestriction r
					WHERE r.member.id = m.id
						AND r.restrictionType
							= sweetgirl.croissant.member.enums.MemberRestrictionType.PROFILE_RESTRICTED
						AND r.startsAt <= :now AND (r.endsAt IS NULL OR r.endsAt > :now))
			ORDER BY m.nicknameNormalized ASC, m.id ASC
			""")
	List<Member> searchSocialByNicknamePrefix(
			@Param("prefix") String prefix,
			@Param("viewerId") Long viewerId,
			@Param("now") LocalDateTime now,
			Pageable pageable);

	/** 추천 후보 id 로 사람을 읽는다. 일반 회원 · 탈퇴 아님만. */
	@Query("SELECT m FROM Member m WHERE m.id IN :memberIds AND m.deletedAt IS NULL AND m.memberType = 'USER'")
	List<Member> findSocialByIdIn(@Param("memberIds") Collection<Long> memberIds);

	/**
	 * 광고 보상으로 닉네임 후보를 하나 뽑아 둔다. 이번 주 무료를 이미 썼을 때만 1 이다.
	 *
	 * <p>광고 뽑기는 횟수 제한이 없다(10/19). 같은 광고의 재시도는 콜백이 {@code transaction_id} 로 먼저 거른다.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
			UPDATE Member m SET m.pendingNickname = :candidate, m.nicknameDrawnAt = :now
			WHERE m.id = :memberId AND m.deletedAt IS NULL
				AND m.nicknameDrawnAt >= :weekStart
			""")
	int drawNicknameWithAdIfAllowed(
			@Param("memberId") Long memberId,
			@Param("candidate") String candidate,
			@Param("now") LocalDateTime now,
			@Param("weekStart") LocalDateTime weekStart);

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

	Optional<Member> findByInviteCodeAndDeletedAtIsNull(String inviteCode);

	boolean existsByInviteCode(String inviteCode);

	@Query(value = "SELECT invite_code FROM member WHERE id = :memberId", nativeQuery = true)
	Optional<String> findInviteCode(@Param("memberId") Long memberId);

	/** 초대 코드가 없을 때만 붙인다. 이미 있으면 0 이다 — 한 번 정해진 코드는 바뀌지 않는다. */
	@Modifying
	@Query(value = """
			UPDATE member SET invite_code = :code
			WHERE id = :memberId AND invite_code IS NULL
			""", nativeQuery = true)
	int assignInviteCodeIfAbsent(@Param("memberId") Long memberId, @Param("code") String code);

	/**
	 * 보관 기한이 지난 탈퇴 회원에 아직 남은 닉네임을 지운다 — 탈퇴할 때 바로 지우기 전(이 기능 이전)에 탈퇴한 회원이 대상이다.
	 * 게스트 병합으로 지워진 게스트(memberType GUEST)는 자동 생성 닉네임이라 건드리지 않는다.
	 */
	@Modifying
	@Query("""
			UPDATE Member m SET m.nickname = :nickname, m.pendingNickname = NULL
			WHERE m.deletedAt < :cutoff AND m.memberType = 'USER'
			AND (m.nickname <> :nickname OR m.pendingNickname IS NOT NULL)
			""")
	int anonymizeMembersDeletedBefore(@Param("cutoff") LocalDateTime cutoff, @Param("nickname") String nickname);
}

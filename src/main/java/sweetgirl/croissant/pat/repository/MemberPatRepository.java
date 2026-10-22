package sweetgirl.croissant.pat.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.pat.entity.MemberPat;
import sweetgirl.croissant.pat.entity.MemberPatId;

public interface MemberPatRepository extends JpaRepository<MemberPat, MemberPatId> {

	/** 오늘 이미 쳤으면 0 — 이중 탭이 PK 충돌로 죽지 않는다. */
	@Modifying
	@Query(value = """
			INSERT INTO member_pat (from_member_id, to_member_id, kst_date, created_at)
			VALUES (:fromMemberId, :toMemberId, :kstDate, :now)
			ON CONFLICT DO NOTHING
			""", nativeQuery = true)
	int insertIfAbsent(
			@Param("fromMemberId") Long fromMemberId,
			@Param("toMemberId") Long toMemberId,
			@Param("kstDate") LocalDate kstDate,
			@Param("now") LocalDateTime now);

	/**
	 * {@code memberId} 가 {@code since} 부터 받은 톡 — 최근 날짜 · 최근 시각 순. 탈퇴한 사람과 차단 관계인 사람은 뺀다.
	 * 같은 사람의 같은 날은 PK 라 한 줄이다.
	 */
	@Query("""
			SELECT p FROM MemberPat p
			JOIN FETCH p.fromMember sender
			WHERE p.id.toMemberId = :memberId
				AND p.id.kstDate >= :since
				AND sender.deletedAt IS NULL
				AND NOT EXISTS (SELECT 1 FROM MemberBlock b
					WHERE (b.blocker.id = :memberId AND b.blockedMember.id = sender.id)
						OR (b.blocker.id = sender.id AND b.blockedMember.id = :memberId))
			ORDER BY p.id.kstDate DESC, p.createdAt DESC
			""")
	List<MemberPat> findReceivedSince(@Param("memberId") Long memberId, @Param("since") LocalDate since);

	/** 두 사람 사이의 톡을 양방향으로 지운다 — 차단 · 탈퇴 때. */
	@Modifying
	@Query("""
			DELETE FROM MemberPat p
			WHERE (p.id.fromMemberId = :memberId AND p.id.toMemberId = :otherId)
				OR (p.id.fromMemberId = :otherId AND p.id.toMemberId = :memberId)
			""")
	int deleteBetween(@Param("memberId") Long memberId, @Param("otherId") Long otherId);
}

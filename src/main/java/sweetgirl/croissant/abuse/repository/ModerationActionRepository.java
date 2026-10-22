package sweetgirl.croissant.abuse.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.abuse.entity.ModerationAction;

public interface ModerationActionRepository extends JpaRepository<ModerationAction, Long> {

	@Query("""
			select a from ModerationAction a
			left join fetch a.member
			left join fetch a.report
			where a.id = :actionId
			""")
	Optional<ModerationAction> findByIdWithMemberAndReport(@Param("actionId") Long actionId);

	/** 탈퇴한 지 cutoff 를 넘긴 사람이 대상인 조치를 지운다 — 보관 기한 뒤. 이의제기를 먼저 지운다. */
	@Modifying
	@Query("""
			DELETE FROM ModerationAction ma
			WHERE ma.member.id IN (SELECT m.id FROM Member m WHERE m.deletedAt < :cutoff)
			""")
	int deleteAllOfMembersDeletedBefore(@Param("cutoff") LocalDateTime cutoff);

	/** 지워질 신고(탈퇴자가 한 · 받은 신고)에서 비롯됐지만 대상은 아직 활성인 조치 — 신고 연결만 끊어 조치 이력은 남긴다. */
	@Modifying
	@Query("""
			UPDATE ModerationAction ma SET ma.report = NULL
			WHERE ma.member.id NOT IN (SELECT m.id FROM Member m WHERE m.deletedAt < :cutoff)
				AND ma.report.id IN (SELECT r.id FROM AbuseReport r
					WHERE r.reporter.id IN (SELECT m.id FROM Member m WHERE m.deletedAt < :cutoff)
						OR r.reportedMember.id IN (SELECT m.id FROM Member m WHERE m.deletedAt < :cutoff))
			""")
	int detachReportsOfMembersDeletedBefore(@Param("cutoff") LocalDateTime cutoff);
}
